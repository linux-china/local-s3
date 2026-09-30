package com.robothy.netty.router;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.utils.MimeTypeUtils;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

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
   * Match resource in classpath.
   */
  private static class ClasspathResourceMatcher extends StaticResourceMatcher {

    private final String resourceRoot;

    private final HttpRequestHandler handler;

    ClasspathResourceMatcher(String path) {
      this.resourceRoot = path.substring("classpath:".length());
      this.handler = (request, response) -> {
        String resourceName = resourceName(request);
        URL url = Thread.currentThread().getContextClassLoader().getResource(resourceName);
        Objects.requireNonNull(url, request.getPath() + " not exist.");
        response.putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), MimeTypeUtils.mimeTypeByFileName(resourceName));
        try (InputStream in = url.openStream()) {
          response.write(in.readAllBytes());
        }
      };
    }

    @Override
    public HttpRequestHandler match(HttpRequest request) {
      String resourceName = resourceName(request);
      URL resource = Thread.currentThread().getContextClassLoader().getResource(resourceName);
      if (null == resource) {
        return null;
      }
      return handler;
    }

    String resourceName(HttpRequest request) {
      if ("/".equals(request.getPath()) || "".equals(request.getPath())) {
        return resourceRoot + "/index.html";
      }
      return resourceRoot + request.getPath();
    }

  }

  /**
   * Match resources in a directory.
   */
  private static class DirectoryResourceMatcher extends StaticResourceMatcher {

    private static final int MAP_THRESHOLD = 10 * 1024 * 1024; // 10MB

    private final HttpRequestHandler handler;

    private final String rootDirectory;

    DirectoryResourceMatcher(String directory) {
      this.rootDirectory = directory;
      this.handler = (request, response) -> {
        String filename = filename(request);
        Path absPath = Paths.get(directory, filename);

        try (FileChannel fileChannel = FileChannel.open(absPath, StandardOpenOption.READ)) {
          long contentLength = fileChannel.size();
          response.status(HttpResponseStatus.OK)
              .putHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), contentLength)
              .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), MimeTypeUtils.mimeTypeByFileName(absPath.toString()));

          if (contentLength > Integer.MAX_VALUE) {
            // The response body is a single ByteBuf, whose capacity is an int.
            throw new IOException(absPath + " is too large to serve, " + contentLength + " bytes.");
          }
          if (contentLength > MAP_THRESHOLD) {
            MappedByteBuffer byteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, contentLength);
            response.write(Unpooled.wrappedBuffer(byteBuffer));
          } else {
            ByteBuffer buf = ByteBuffer.allocate((int) contentLength);
            while (buf.hasRemaining()) {
              // The file is shorter than its size, e.g. it was truncated while being read.
              if (fileChannel.read(buf) < 0) {
                throw new EOFException(absPath + " ended after " + buf.position() + " of " + contentLength + " bytes.");
              }
            }
            buf.flip();
            response.write(Unpooled.wrappedBuffer(buf));
          }

        }
      };
    }

    @Override
    public HttpRequestHandler match(HttpRequest request) {
      if (request.getMethod() != HttpMethod.GET) {
        return null;
      }

      String filename = filename(request);
      Path absPath = Paths.get(rootDirectory, filename);
      if (!absPath.toFile().exists()) {
        return null;
      }

      return handler;
    }

    private String filename(HttpRequest request) {
      if ("".equals(request.getPath()) || "/".equals(request.getPath())) {
        return "index.html";
      }

      return Paths.get(request.getPath()).normalize().toString();
    }

  }

}
