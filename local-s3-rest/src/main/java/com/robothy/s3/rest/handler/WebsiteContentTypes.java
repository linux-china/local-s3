package com.robothy.s3.rest.handler;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The content type of a static website file, guessed from the extension of its key.
 *
 * <p>An object that was stored without a {@code Content-Type}, which is what a file uploaded by a tool that doesn't
 * set one gets, is served as {@code binary/octet-stream}: a browser then downloads the page instead of rendering it,
 * and ignores a stylesheet or a script altogether. The S3 API serves such an object as it was stored, since a client
 * reads its bytes; the website endpoint guesses instead, so that a directory of files that was copied into a bucket
 * is a working site.
 */
final class WebsiteContentTypes {

  /**
   * The content types of the extensions a static site is made of. Text types name UTF-8, which is what a browser
   * needs to be told to render a page of another alphabet correctly.
   */
  private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
      Map.entry("html", "text/html; charset=utf-8"),
      Map.entry("htm", "text/html; charset=utf-8"),
      Map.entry("xhtml", "application/xhtml+xml; charset=utf-8"),
      Map.entry("css", "text/css; charset=utf-8"),
      Map.entry("js", "text/javascript; charset=utf-8"),
      Map.entry("mjs", "text/javascript; charset=utf-8"),
      Map.entry("json", "application/json; charset=utf-8"),
      Map.entry("map", "application/json; charset=utf-8"),
      Map.entry("xml", "application/xml; charset=utf-8"),
      Map.entry("txt", "text/plain; charset=utf-8"),
      Map.entry("md", "text/markdown; charset=utf-8"),
      Map.entry("csv", "text/csv; charset=utf-8"),
      Map.entry("svg", "image/svg+xml"),
      Map.entry("png", "image/png"),
      Map.entry("jpg", "image/jpeg"),
      Map.entry("jpeg", "image/jpeg"),
      Map.entry("gif", "image/gif"),
      Map.entry("webp", "image/webp"),
      Map.entry("avif", "image/avif"),
      Map.entry("ico", "image/x-icon"),
      Map.entry("bmp", "image/bmp"),
      Map.entry("woff", "font/woff"),
      Map.entry("woff2", "font/woff2"),
      Map.entry("ttf", "font/ttf"),
      Map.entry("otf", "font/otf"),
      Map.entry("eot", "application/vnd.ms-fontobject"),
      Map.entry("pdf", "application/pdf"),
      Map.entry("wasm", "application/wasm"),
      Map.entry("mp4", "video/mp4"),
      Map.entry("webm", "video/webm"),
      Map.entry("mp3", "audio/mpeg"),
      Map.entry("ogg", "audio/ogg"),
      Map.entry("wav", "audio/wav"),
      Map.entry("zip", "application/zip"),
      Map.entry("gz", "application/gzip"),
      Map.entry("webmanifest", "application/manifest+json"));

  private WebsiteContentTypes() {
  }

  /**
   * The content type of a key, by its extension.
   *
   * @param key the object key, e.g. {@code css/site.css}.
   * @return the content type; empty if the extension isn't one of a static site, which leaves the object the content
   *     type it was stored with.
   */
  static Optional<String> of(String key) {
    int dot = key.lastIndexOf('.');
    int slash = key.lastIndexOf('/');
    if (dot < 0 || dot < slash || dot == key.length() - 1) {
      return Optional.empty();
    }
    return Optional.ofNullable(BY_EXTENSION.get(key.substring(dot + 1).toLowerCase(Locale.ROOT)));
  }

}
