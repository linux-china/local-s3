package com.robothy.netty.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.stream.ChunkedInput;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class RouterHttpResponse {

  // Header names are case-insensitive (RFC 9110); a case-sensitive map would let "Content-Length" and
  // "content-length" both be emitted.
  @Getter(AccessLevel.NONE)
  private final Map<String, List<String>> headerValues = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

  @Getter(AccessLevel.NONE)
  private final Map<String, String> headers = new SingleValueHeaders();

  private HttpResponseStatus status;

  // No component limit: consolidating the components of a large body would copy it over and over.
  private final CompositeByteBuf body = Unpooled.compositeBuffer(Integer.MAX_VALUE);

  /**
   * The body that is streamed chunk by chunk instead of the buffered {@link #body}, e.g. a file; {@code null} if the
   * buffered body is sent.
   */
  private ChunkedInput<ByteBuf> chunkedBody;

  public RouterHttpResponse write(String content) {
    if (content != null) {
      write(content.getBytes(StandardCharsets.UTF_8));
    }
    return this;
  }

  public RouterHttpResponse write(byte[] bytes) {
    if (bytes != null) {
      body.addComponent(true, Unpooled.copiedBuffer(bytes));
    }
    return this;
  }

  public RouterHttpResponse write(ByteBuf buf) {
    if (buf != null) {
      body.addComponent(true, buf);
    }
    return this;
  }

  /**
   * Stream the body from {@code input} while it is written to the connection, instead of buffering it in memory. The
   * response takes the input over and closes it once it is written, or dropped, e.g. for a {@code HEAD} request;
   * a previous chunked body is closed. What was {@linkplain #write(ByteBuf) written} to the buffered body isn't sent.
   *
   * <p>The {@code Content-Length} is the {@linkplain ChunkedInput#length() length} of the input unless it is set;
   * an input of unknown length is sent with {@code Transfer-Encoding: chunked}.
   *
   * @param input the response body.
   * @return this response.
   */
  public RouterHttpResponse chunkedBody(ChunkedInput<ByteBuf> input) {
    ChunkedInput<ByteBuf> previous = detachChunkedBody();
    if (previous != null && previous != input) {
      closeQuietly(previous);
    }
    this.chunkedBody = input;
    return this;
  }

  /**
   * Take the chunked body over, e.g. to write it; the caller closes it.
   *
   * @return the chunked body; or {@code null} if there is none.
   */
  public ChunkedInput<ByteBuf> detachChunkedBody() {
    ChunkedInput<ByteBuf> input = this.chunkedBody;
    this.chunkedBody = null;
    return input;
  }

  /**
   * Close {@code input}, logging instead of throwing a failure, as there is nothing left to do about it.
   *
   * @param input the input to close; {@code null} is ignored.
   */
  public static void closeQuietly(ChunkedInput<?> input) {
    if (input == null) {
      return;
    }
    try {
      input.close();
    } catch (Exception e) {
      log.debug("Failed to close the chunked response body.", e);
    }
  }

  public RouterHttpResponse status(HttpResponseStatus status) {
    this.status = status;
    return this;
  }

  /**
   * Set a header, replacing all the values it had.
   *
   * @param value the header value; {@code null} removes the header rather than sending the text "null".
   */
  public RouterHttpResponse putHeader(String key, Object value) {
    if (value == null) {
      headers.remove(key);
    } else {
      headers.put(key, String.valueOf(value));
    }
    return this;
  }

  /**
   * Add a value to a header, keeping the values it had, e.g. for {@code Set-Cookie}, whose values can't be joined with
   * commas. Each value is sent as a header line of its own.
   *
   * @param value the header value; {@code null} is ignored rather than sent as the text "null".
   */
  public RouterHttpResponse addHeader(String key, Object value) {
    if (value != null) {
      headerValues.computeIfAbsent(key, k -> new ArrayList<>(1)).add(String.valueOf(value));
    }
    return this;
  }

  /**
   * The headers, with the last value of each header name. The map is a live view: putting a header replaces all its
   * values, and removing it removes all of them. See {@link #getHeaderValues(String)} and {@link #getAllHeaders()} for
   * all the values.
   */
  public Map<String, String> getHeaders() {
    return headers;
  }

  /**
   * @return the values of a header, in the order they were added; empty if there is none.
   */
  public List<String> getHeaderValues(String name) {
    List<String> values = headerValues.get(name);
    return values == null ? List.of() : Collections.unmodifiableList(values);
  }

  /**
   * @return all the values of every header, by case-insensitive name; unmodifiable.
   */
  public Map<String, List<String>> getAllHeaders() {
    return Collections.unmodifiableMap(headerValues);
  }

  private static String last(List<String> values) {
    return values == null || values.isEmpty() ? null : values.getLast();
  }

  /**
   * A {@code Map<String, String>} view of {@link #headerValues} that shows the last value of each header.
   */
  private class SingleValueHeaders extends AbstractMap<String, String> {

    @Override
    public int size() {
      return headerValues.size();
    }

    @Override
    public boolean containsKey(Object key) {
      return key instanceof String && headerValues.containsKey(key);
    }

    @Override
    public String get(Object key) {
      return key instanceof String ? last(headerValues.get(key)) : null;
    }

    @Override
    public String put(String key, String value) {
      Objects.requireNonNull(value, "value");
      List<String> values = new ArrayList<>(1);
      values.add(value);
      return last(headerValues.put(key, values));
    }

    @Override
    public String remove(Object key) {
      return key instanceof String ? last(headerValues.remove(key)) : null;
    }

    @Override
    public void clear() {
      headerValues.clear();
    }

    @Override
    public Set<Entry<String, String>> entrySet() {
      return new AbstractSet<>() {
        @Override
        public int size() {
          return headerValues.size();
        }

        @Override
        public Iterator<Entry<String, String>> iterator() {
          Iterator<Entry<String, List<String>>> it = headerValues.entrySet().iterator();
          return new Iterator<>() {
            @Override
            public boolean hasNext() {
              return it.hasNext();
            }

            @Override
            public Entry<String, String> next() {
              Entry<String, List<String>> entry = it.next();
              return new SimpleEntry<>(entry.getKey(), last(entry.getValue())) {
                @Override
                public String setValue(String value) {
                  Objects.requireNonNull(value, "value");
                  super.setValue(value);
                  List<String> values = new ArrayList<>(1);
                  values.add(value);
                  return last(entry.setValue(values));
                }
              };
            }

            @Override
            public void remove() {
              it.remove();
            }
          };
        }
      };
    }
  }

}
