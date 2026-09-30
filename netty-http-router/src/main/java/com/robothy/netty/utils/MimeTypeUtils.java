package com.robothy.netty.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class MimeTypeUtils {

  private static final Map<String, String> extensionToMimeType;

  static {
    Map<String, String> map = new HashMap<>();

    // Web
    map.put("html", "text/html; charset=utf-8");
    map.put("htm", "text/html; charset=utf-8");
    map.put("css", "text/css; charset=utf-8");
    map.put("js", "text/javascript; charset=utf-8");
    map.put("mjs", "text/javascript; charset=utf-8");
    map.put("ts", "text/typescript; charset=utf-8");
    map.put("json", "application/json");
    map.put("jsonld", "application/ld+json");
    map.put("webmanifest", "application/manifest+json");
    map.put("map", "application/json");
    map.put("xml", "application/xml");
    map.put("wasm", "application/wasm");

    // Text
    map.put("txt", "text/plain; charset=utf-8");
    map.put("log", "text/plain; charset=utf-8");
    map.put("ini", "text/plain; charset=utf-8");
    map.put("md", "text/markdown; charset=utf-8");
    map.put("csv", "text/csv; charset=utf-8");
    map.put("tsv", "text/tab-separated-values; charset=utf-8");
    map.put("sql", "application/sql");
    map.put("toml", "application/toml");
    map.put("yaml", "application/yaml");
    map.put("yml", "application/yaml");
    map.put("ndjson", "application/x-ndjson");
    map.put("jsonl", "application/x-ndjson");

    // Data files
    map.put("parquet", "application/vnd.apache.parquet");
    map.put("avro", "application/avro");
    map.put("orc", "application/x-orc");
    map.put("arrow", "application/vnd.apache.arrow.file");
    map.put("feather", "application/vnd.apache.arrow.file");

    // Archives
    map.put("zip", "application/zip");
    map.put("gz", "application/gzip");
    map.put("tar", "application/x-tar");
    map.put("bz2", "application/x-bzip2");
    map.put("zst", "application/zstd");
    map.put("xz", "application/x-xz");
    map.put("7z", "application/x-7z-compressed");
    map.put("rar", "application/vnd.rar");

    // Images
    map.put("png", "image/png");
    map.put("jpg", "image/jpeg");
    map.put("jpeg", "image/jpeg");
    map.put("gif", "image/gif");
    map.put("webp", "image/webp");
    map.put("avif", "image/avif");
    map.put("svg", "image/svg+xml");
    map.put("ico", "image/x-icon");
    map.put("bmp", "image/bmp");
    map.put("tiff", "image/tiff");
    map.put("tif", "image/tiff");

    // Fonts
    map.put("woff", "font/woff");
    map.put("woff2", "font/woff2");
    map.put("ttf", "font/ttf");
    map.put("otf", "font/otf");

    // Audio and video
    map.put("mp3", "audio/mpeg");
    map.put("wav", "audio/wav");
    map.put("m4a", "audio/mp4");
    map.put("aac", "audio/aac");
    map.put("ogg", "audio/ogg");
    map.put("oga", "audio/ogg");
    map.put("opus", "audio/ogg");
    map.put("flac", "audio/flac");
    map.put("ogv", "video/ogg");
    map.put("mp4", "video/mp4");
    map.put("mov", "video/quicktime");
    map.put("webm", "video/webm");

    // Documents
    map.put("pdf", "application/pdf");
    map.put("doc", "application/msword");
    map.put("ppt", "application/vnd.ms-powerpoint");
    map.put("xls", "application/vnd.ms-excel");
    map.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    map.put("dotx", "application/vnd.openxmlformats-officedocument.wordprocessingml.template");
    map.put("docm", "application/vnd.ms-word.document.macroEnabled.12");
    map.put("dotm", "application/vnd.ms-word.template.macroEnabled.12");
    map.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    map.put("ppsx", "application/vnd.openxmlformats-officedocument.presentationml.slideshow");
    map.put("potx", "application/vnd.openxmlformats-officedocument.presentationml.template");
    map.put("pptm", "application/vnd.ms-powerpoint.presentation.macroEnabled.12");
    map.put("ppsm", "application/vnd.ms-powerpoint.slideshow.macroEnabled.12");
    map.put("potm", "application/vnd.ms-powerpoint.template.macroEnabled.12");
    map.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    map.put("xltx", "application/vnd.openxmlformats-officedocument.spreadsheetml.template");
    map.put("xlsm", "application/vnd.ms-excel.sheet.macroEnabled.12");
    map.put("xltm", "application/vnd.ms-excel.template.macroEnabled.12");

    extensionToMimeType = Collections.unmodifiableMap(map);
  }

  public static final String DEFAULT_MIME_TYPE = "application/octet-stream";

  /**
   * Get the MIME type by the file extension, ignoring case.
   *
   * @param extension file extension without the leading dot, e.g. {@code html}.
   * @return the MIME type; or {@code application/octet-stream} if the extension is unknown.
   */
  public static String mimeTypeByFileExtension(String extension) {
    if (extension == null) {
      return DEFAULT_MIME_TYPE;
    }
    return extensionToMimeType.getOrDefault(extension.toLowerCase(Locale.ROOT), DEFAULT_MIME_TYPE);
  }

  /**
   * Get the MIME type by the file name. Look up the extension in the built-in table
   *
   * @param fileName file name or path, e.g. {@code static/index.html}.
   * @return the MIME type, never {@code null}; {@code application/octet-stream} if it is unknown.
   */
  public static String mimeTypeByFileName(String fileName) {
    Objects.requireNonNull(fileName, "The file name shouldn't be null.");
    String name = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);
    int dot = name.lastIndexOf('.');
    return dot < 0 ? DEFAULT_MIME_TYPE : mimeTypeByFileExtension(name.substring(dot + 1));
  }

}
