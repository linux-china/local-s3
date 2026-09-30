package com.robothy.netty.router;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.netty.utils.MimeTypeUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.stream.ChunkedNioFile;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;

/**
 * Serves the files under a root directory or classpath resource path to {@code GET} and {@code HEAD} requests.
 *
 * <p>A request path is resolved against the root only if it has no {@code ..} segment and no backslash, so it cannot
 * address a file outside of the root; only regular files are served, not directories.
 */
abstract class StaticResourceMatcher {

  abstract HttpRequestHandler match(HttpRequest request);

  static StaticResourceMatcher create(String path) {
    Objects.requireNonNull(path, "The static resource path shouldn't be null.");
    if (path.startsWith("classpath:")) {
      return new ClasspathResourceMatcher(path);
    } else {
      return new DirectoryResourceMatcher(path);
    }
  }

  /**
   * Get the path of the requested file relative to the root, e.g. {@code css/app.css} of {@code /css//app.css}; the
   * root path is {@code index.html}.
   *
   * @return the relative path; or {@code null} if the request isn't a {@code GET} or {@code HEAD}, or the path has a
   *     {@code ..} segment or a backslash, which could address a file outside of the root.
   */
  static String relativePath(HttpRequest request) {
    HttpMethod method = request.getMethod();
    if ((!HttpMethod.GET.equals(method) && !HttpMethod.HEAD.equals(method)) || request.getPath() == null) {
      return null;
    }
    List<String> segments = new ArrayList<>();
    for (String segment : request.getPath().split("/")) {
      if (segment.equals("..") || segment.indexOf('\\') >= 0) {
        return null;
      }
      if (!segment.isEmpty() && !segment.equals(".")) {
        segments.add(segment);
      }
    }
    return segments.isEmpty() ? "index.html" : String.join("/", segments);
  }

  /**
   * Match resource in classpath.
   */
  private static class ClasspathResourceMatcher extends StaticResourceMatcher {

    private final String resourceRoot;

    /**
     * Captured when the root is set: the context class loader of a netty executor thread is the one of whichever
     * thread started it, which isn't predictable, e.g. in an IDE plugin or a Spring Boot fat jar.
     */
    private final ClassLoader classLoader;

    ClasspathResourceMatcher(String path) {
      this.resourceRoot = path.substring("classpath:".length());
      ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
      this.classLoader = contextClassLoader == null ? StaticResourceMatcher.class.getClassLoader() : contextClassLoader;
    }

    @Override
    public HttpRequestHandler match(HttpRequest request) {
      String relativePath = relativePath(request);
      if (relativePath == null) {
        return null;
      }
      String resourceName = resourceRoot + "/" + relativePath;
      URL url = classLoader.getResource(resourceName);
      if (url == null || !isRegularFile(url)) {
        return null;
      }
      return (req, response) -> {
        response.putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), MimeTypeUtils.mimeTypeByFileName(resourceName));
        try (InputStream in = url.openStream()) {
          response.write(in.readAllBytes());
        }
      };
    }

    /**
     * A directory is a resource too, and a {@code file:} one is read as a listing of its files.
     */
    private static boolean isRegularFile(URL url) {
      try {
        if ("file".equals(url.getProtocol())) {
          return Files.isRegularFile(Path.of(url.toURI()));
        }
        if (url.openConnection() instanceof JarURLConnection connection) {
          JarEntry entry = connection.getJarEntry();
          return entry != null && !entry.isDirectory();
        }
        return !url.getPath().endsWith("/");
      } catch (IOException | URISyntaxException | IllegalArgumentException e) {
        return false;
      }
    }

  }

  /**
   * Match resources in a directory.
   */
  private static class DirectoryResourceMatcher extends StaticResourceMatcher {

    private final Path rootDirectory;

    DirectoryResourceMatcher(String directory) {
      this.rootDirectory = Path.of(directory).toAbsolutePath().normalize();
      // Fail fast: a wrong root would otherwise only show as every static resource being not found.
      if (!Files.isDirectory(rootDirectory)) {
        throw new IllegalArgumentException("The static resource root " + rootDirectory + " is not a directory.");
      }
    }

    @Override
    public HttpRequestHandler match(HttpRequest request) {
      String relativePath = relativePath(request);
      if (relativePath == null) {
        return null;
      }
      Path absPath;
      try {
        absPath = rootDirectory.resolve(relativePath).normalize();
      } catch (InvalidPathException e) {
        return null;
      }
      // relativePath has no "..", this is a second line of defense, e.g. against "C:" on Windows.
      if (!absPath.startsWith(rootDirectory) || !Files.isRegularFile(absPath)) {
        return null;
      }
      return (req, response) -> serve(absPath, response);
    }

    private static void serve(Path absPath, HttpResponse response) throws IOException {
      FileChannel fileChannel = FileChannel.open(absPath, StandardOpenOption.READ);
      try {
        long contentLength = fileChannel.size();
        response.status(HttpResponseStatus.OK)
            .putHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), contentLength)
            .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), MimeTypeUtils.mimeTypeByFileName(absPath.toString()))
            // Streamed as the connection accepts it, rather than read into memory; the response closes the file.
            .chunkedBody(new FileChunkedInput(absPath, fileChannel, contentLength));
      } catch (IOException | RuntimeException e) {
        fileChannel.close();
        throw e;
      }
    }

    /**
     * A {@linkplain ChunkedNioFile} that fails if the file ends before its length, e.g. it was truncated while being
     * sent: {@linkplain ChunkedNioFile} would return empty chunks without ever reaching the end.
     */
    static final class FileChunkedInput extends ChunkedNioFile {

      static final int CHUNK_SIZE = 64 * 1024;

      private final Path path;

      FileChunkedInput(Path path, FileChannel fileChannel, long length) throws IOException {
        super(fileChannel, 0, length, CHUNK_SIZE);
        this.path = path;
      }

      @Override
      public ByteBuf readChunk(ByteBufAllocator allocator) throws Exception {
        long expected = Math.min(CHUNK_SIZE, endOffset() - currentOffset());
        ByteBuf chunk = super.readChunk(allocator);
        if (chunk != null && chunk.readableBytes() < expected) {
          long offset = currentOffset();
          chunk.release();
          throw new EOFException(path + " ended after " + offset + " of " + endOffset() + " bytes.");
        }
        return chunk;
      }

    }
  }

}
