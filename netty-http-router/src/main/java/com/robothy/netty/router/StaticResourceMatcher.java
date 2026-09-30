package com.robothy.netty.router;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.netty.utils.MimeTypeUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.DateFormatter;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.stream.ChunkedNioFile;
import io.netty.handler.stream.ChunkedStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import lombok.extern.slf4j.Slf4j;

/**
 * Serves the files under a root directory or classpath resource path to {@code GET} and {@code HEAD} requests.
 *
 * <p>A request path is resolved against the root only if it has no {@code ..} segment and no backslash, so it cannot
 * address a file outside of the root; only regular files are served, not directories. A directory with an
 * {@code index.html} is served as that file, e.g. {@code /docs/} as {@code docs/index.html}, and {@code /docs} is
 * redirected to {@code /docs/} so that the relative links of the page resolve under the directory.
 *
 * <p>A file is sent with {@code Last-Modified}, a weak {@code ETag} made of its length and modification time, and
 * {@code Cache-Control: no-cache}, so a browser revalidates it on every use: a request with a matching
 * {@code If-None-Match}, or else an {@code If-Modified-Since} not before the modification time, is answered with
 * {@code 304 Not Modified} and no content.
 */
@Slf4j
abstract class StaticResourceMatcher {

  static final int CHUNK_SIZE = 64 * 1024;

  static final String INDEX = "index.html";

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
   * root path is {@code index.html}. A path may be of a directory, which is served by its {@code index.html}.
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
    return segments.isEmpty() ? INDEX : String.join("/", segments);
  }

  /**
   * Redirect the path of a directory that has an {@code index.html} to the path with a trailing '/', keeping the
   * query, e.g. {@code /docs?a=1} to {@code /docs/?a=1}.
   *
   * @return the redirecting handler; or {@code null} if the path already ends with '/', and the index is served.
   */
  static HttpRequestHandler redirectToDirectory(HttpRequest request) {
    if (request.getPath().endsWith("/")) {
      return null;
    }
    String uri = request.getUri() == null ? request.getPath() : request.getUri();
    int queryStart = uri.indexOf('?');
    String location = queryStart < 0 ? uri + "/" : uri.substring(0, queryStart) + "/" + uri.substring(queryStart);
    return (req, response) -> response.status(HttpResponseStatus.MOVED_PERMANENTLY)
        .putHeader(HttpHeaderNames.LOCATION.toString(), location);
  }

  /**
   * Put the validators of a resource and answer {@code 304 Not Modified} if the client has it already.
   *
   * @param lastModified the modification time in milliseconds; or {@code 0} if it is unknown, then the resource has
   *     no validators and is always sent.
   * @param length the length of the resource; or {@code -1} if it is unknown.
   * @return {@code true} if the response is a {@code 304}, which has no content.
   */
  static boolean notModified(HttpRequest request, HttpResponse response, long lastModified, long length) {
    response.putHeader(HttpHeaderNames.CACHE_CONTROL.toString(), "no-cache");
    if (lastModified <= 0) {
      return false;
    }
    // An HTTP date has seconds precision.
    long lastModifiedSeconds = lastModified / 1000;
    String etag = "W/\"" + Long.toHexString(length) + "-" + Long.toHexString(lastModified) + "\"";
    response.putHeader(HttpHeaderNames.LAST_MODIFIED.toString(),
            DateFormatter.format(new Date(lastModifiedSeconds * 1000)))
        .putHeader(HttpHeaderNames.ETAG.toString(), etag);
    boolean notModified;
    String ifNoneMatch = request.header(HttpHeaderNames.IF_NONE_MATCH).orElse(null);
    if (ifNoneMatch != null) {
      // If-Modified-Since is ignored along with If-None-Match (RFC 9110, section 13.1.3).
      notModified = etagMatches(ifNoneMatch, etag);
    } else {
      Date since = request.header(HttpHeaderNames.IF_MODIFIED_SINCE).map(DateFormatter::parseHttpDate).orElse(null);
      notModified = since != null && lastModifiedSeconds <= since.getTime() / 1000;
    }
    if (notModified) {
      response.status(HttpResponseStatus.NOT_MODIFIED);
    }
    return notModified;
  }

  /**
   * The weak comparison of {@code If-None-Match}: {@code *}, or any of its tags equal to the ETag, ignoring the
   * {@code W/} prefixes.
   */
  private static boolean etagMatches(String ifNoneMatch, String etag) {
    String opaqueTag = etag.substring(2);
    for (String tag : ifNoneMatch.split(",")) {
      tag = tag.trim();
      if (tag.equals("*") || (tag.startsWith("W/") ? tag.substring(2) : tag).equals(opaqueTag)) {
        return true;
      }
    }
    return false;
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
      this.resourceRoot = trimSlashes(path.substring("classpath:".length()));
      ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
      this.classLoader = contextClassLoader == null ? StaticResourceMatcher.class.getClassLoader() : contextClassLoader;
      // Not an error: a jar may have no entries for its directories, so the root can't always be found by its name.
      if (!resourceRoot.isEmpty() && classLoader.getResource(resourceRoot) == null) {
        log.warn("The static resource root '{}' is not found on the classpath, its resources may be not found.", path);
      }
    }

    /**
     * Resource names of a class loader don't start with '/', e.g. {@code classpath:/static} is the root
     * {@code static}, and a trailing '/' would double the separator of the resource names.
     */
    private static String trimSlashes(String root) {
      int start = 0;
      int end = root.length();
      while (start < end && root.charAt(start) == '/') {
        start++;
      }
      while (end > start && root.charAt(end - 1) == '/') {
        end--;
      }
      return root.substring(start, end);
    }

    @Override
    public HttpRequestHandler match(HttpRequest request) {
      String relativePath = relativePath(request);
      if (relativePath == null) {
        return null;
      }
      String name = resourceRoot.isEmpty() ? relativePath : resourceRoot + "/" + relativePath;
      URL resource = classLoader.getResource(name);
      if (resource == null || !isRegularFile(resource)) {
        // A jar may have no entry for a directory, so it is found by its index.
        resource = classLoader.getResource(name + "/" + INDEX);
        if (resource == null || !isRegularFile(resource)) {
          return null;
        }
        HttpRequestHandler redirect = redirectToDirectory(request);
        if (redirect != null) {
          return redirect;
        }
        name = name + "/" + INDEX;
      }
      String resourceName = name;
      URL url = resource;
      return (req, response) -> {
        response.putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), MimeTypeUtils.mimeTypeByFileName(resourceName));
        long[] lengthAndLastModified = lengthAndLastModified(url);
        long length = lengthAndLastModified[0];
        if (notModified(req, response, lengthAndLastModified[1], length)) {
          return;
        }
        if (HttpMethod.HEAD.equals(req.getMethod())) {
          if (length >= 0) {
            response.putHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), length);
          }
          return;
        }
        URLConnection connection = url.openConnection();
        InputStream in = connection.getInputStream();
        try {
          long contentLength = connection.getContentLengthLong();
          // Unknown, e.g. of an unusual protocol: the body is sent with Transfer-Encoding: chunked.
          if (contentLength >= 0) {
            response.putHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), contentLength);
          }
          // Streamed as the connection accepts it, rather than read into memory; the response closes the stream.
          response.chunkedBody(new ChunkedStream(in, CHUNK_SIZE));
        } catch (RuntimeException e) {
          in.close();
          throw e;
        }
      };
    }

    /**
     * The length and the modification time of a resource without reading it.
     *
     * @return the length, or {@code -1} if it is unknown; and the modification time in milliseconds, or {@code 0} if
     *     it is unknown.
     */
    private static long[] lengthAndLastModified(URL url) throws IOException {
      if ("file".equals(url.getProtocol())) {
        try {
          BasicFileAttributes attributes = Files.readAttributes(Path.of(url.toURI()), BasicFileAttributes.class);
          return new long[] {attributes.size(), attributes.lastModifiedTime().toMillis()};
        } catch (URISyntaxException | IllegalArgumentException e) {
          return new long[] {-1, 0};
        }
      }
      URLConnection connection = url.openConnection();
      if (connection instanceof JarURLConnection jarConnection) {
        // The jar file is shared by the connections, so there is nothing to close. The time of the entry rather than
        // the one of the connection, which is of the jar file.
        JarEntry entry = jarConnection.getJarEntry();
        return new long[] {entry.getSize(), Math.max(entry.getTime(), 0)};
      }
      // Other connections may open the resource to get its length, which is closed unread.
      try (InputStream ignored = connection.getInputStream()) {
        return new long[] {connection.getContentLengthLong(), connection.getLastModified()};
      }
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
      if (!absPath.startsWith(rootDirectory)) {
        return null;
      }
      if (Files.isDirectory(absPath) && Files.isRegularFile(absPath.resolve(INDEX))) {
        HttpRequestHandler redirect = redirectToDirectory(request);
        if (redirect != null) {
          return redirect;
        }
        absPath = absPath.resolve(INDEX);
      } else if (!Files.isRegularFile(absPath)) {
        return null;
      }
      Path file = absPath;
      return (req, response) -> {
        response.putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), MimeTypeUtils.mimeTypeByFileName(file.toString()));
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        if (notModified(req, response, attributes.lastModifiedTime().toMillis(), attributes.size())) {
          return;
        }
        if (HttpMethod.HEAD.equals(req.getMethod())) {
          // Only the headers are sent, so the file isn't opened.
          response.status(HttpResponseStatus.OK)
              .putHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), attributes.size());
        } else {
          serve(file, response);
        }
      };
    }

    private static void serve(Path absPath, HttpResponse response) throws IOException {
      FileChannel fileChannel = FileChannel.open(absPath, StandardOpenOption.READ);
      try {
        long contentLength = fileChannel.size();
        response.status(HttpResponseStatus.OK)
            .putHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), contentLength)
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
