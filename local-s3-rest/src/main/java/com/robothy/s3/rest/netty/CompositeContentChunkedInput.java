package com.robothy.s3.rest.netty;

import com.robothy.s3.core.storage.CompositeInputStream;
import com.robothy.s3.core.storage.FileRegionInputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.FileRegion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedInput;
import io.netty.handler.stream.ChunkedStream;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Sends the content of an object that is stored in parts, i.e. a {@linkplain CompositeInputStream}, part by part on a
 * plaintext connection: a file-backed part as a zero-copy {@linkplain DefaultFileRegion}, and any other part chunk by
 * chunk, so that no file is read through the Java heap on the event loop.
 *
 * <p>Only one part is open at a time. The next part is opened once the region of the previous one has been written
 * and released, which closes its file: an object of hundreds of parts, as Spark or DuckDB write them, is sent with
 * one open file rather than one per part. A file region takes no room in the outbound buffer, so the channel stays
 * writable however many of them are queued; {@linkplain ChunkedWriteHandler} would otherwise pull every part of the
 * object in one go. While a region is in flight, this input answers {@code null}, which suspends the transfer, and
 * the release of the region {@linkplain ChunkedWriteHandler#resumeTransfer() resumes} it.
 *
 * <p>The transfer is resumed through the executor of the channel rather than in place, so that sending a part
 * doesn't recurse into sending the next one: the parts of a large object would otherwise fill the stack.
 */
final class CompositeContentChunkedInput implements ChunkedInput<Object> {

  private final CompositeInputStream content;

  private final int chunkSize;

  /**
   * Resumes the transfer after a region was written; does nothing if the pipeline holds no
   * {@linkplain ChunkedWriteHandler}, e.g. in a test that reads the chunks itself.
   */
  private final Runnable resume;

  /**
   * The index of the next part to open.
   */
  private int next;

  /**
   * The part that is being sent chunk by chunk, i.e. one that isn't file-backed; {@code null} if none is.
   */
  private ChunkedStream chunks;

  /**
   * Whether a file region that hasn't been released yet holds a part open.
   */
  private volatile boolean regionInFlight;

  /**
   * Whether the last chunk, which ends the HTTP message, was answered.
   */
  private boolean ended;

  private long progress;

  private CompositeContentChunkedInput(CompositeInputStream content, int chunkSize, Runnable resume) {
    this.content = Objects.requireNonNull(content);
    this.chunkSize = chunkSize;
    this.resume = Objects.requireNonNull(resume);
  }

  /**
   * Create the input of a response that sends {@code content} on the connection of {@code ctx}.
   *
   * @param content the content of the object, stored in parts.
   * @param ctx the context of the connection, whose {@linkplain ChunkedWriteHandler} writes the parts.
   * @param chunkSize the number of bytes to send at a time of a part that isn't file-backed.
   * @return the input.
   */
  static CompositeContentChunkedInput of(CompositeInputStream content, ChannelHandlerContext ctx, int chunkSize) {
    ChunkedWriteHandler writer = ctx.pipeline().get(ChunkedWriteHandler.class);
    Executor executor = ctx.executor();
    Runnable resume = writer == null ? () -> { } : () -> executor.execute(writer::resumeTransfer);
    return new CompositeContentChunkedInput(content, chunkSize, resume);
  }

  @Override
  public boolean isEndOfInput() {
    return ended;
  }

  @Deprecated
  @Override
  public Object readChunk(ChannelHandlerContext ctx) throws Exception {
    return readChunk(ctx.alloc());
  }

  @Override
  public Object readChunk(ByteBufAllocator allocator) throws Exception {
    while (true) {
      if (chunks != null) {
        ByteBuf chunk = chunks.readChunk(allocator);
        if (chunk != null) {
          progress += chunk.readableBytes();
          return chunk;
        }
        // The part is exhausted; the stream of the part is closed with it.
        ChunkedStream sent = chunks;
        chunks = null;
        sent.close();
        continue;
      }
      if (regionInFlight) {
        // Suspends the transfer; releasing the region resumes it.
        return null;
      }
      if (next < content.partCount()) {
        InputStream part = content.openPart(next++);
        if (part instanceof FileRegionInputStream file) {
          if (file.getCount() == 0) {
            file.close();
            continue;
          }
          regionInFlight = true;
          progress += file.getCount();
          return new ReleaseNotifyingFileRegion(file.getChannel(), file.getPosition(), file.getCount());
        }
        chunks = new ChunkedStream(part, chunkSize);
        continue;
      }
      ended = true;
      return LastHttpContent.EMPTY_LAST_CONTENT;
    }
  }

  @Override
  public long length() {
    return -1;
  }

  @Override
  public long progress() {
    return progress;
  }

  /**
   * Close the part that is being sent chunk by chunk, if any, and release the retention of the content, so that
   * content which was deleted while it was read is deleted now. A part that is in flight as a file region is closed
   * when the region is released.
   */
  @Override
  public void close() throws IOException {
    try {
      if (chunks != null) {
        ChunkedStream open = chunks;
        chunks = null;
        open.close();
      }
    } catch (Exception e) {
      throw new IOException("Failed to close a part of the content.", e);
    } finally {
      content.close();
    }
  }

  /**
   * A {@linkplain FileRegion} of a part that lets the next part be opened once it is written and its file is closed.
   */
  private final class ReleaseNotifyingFileRegion extends DefaultFileRegion {

    private ReleaseNotifyingFileRegion(FileChannel file, long position, long count) {
      super(file, position, count);
    }

    @Override
    protected void deallocate() {
      try {
        super.deallocate();
      } finally {
        regionInFlight = false;
        resume.run();
      }
    }
  }

}
