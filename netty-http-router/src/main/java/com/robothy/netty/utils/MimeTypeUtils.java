package com.robothy.netty.utils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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

  public static String mimeTypeByFileExtension(String extension) {
    return extensionToMimeType.getOrDefault(extension, "application/octet-stream");
  }

}
