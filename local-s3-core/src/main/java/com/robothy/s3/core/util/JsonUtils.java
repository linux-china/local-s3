package com.robothy.s3.core.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.io.File;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import lombok.SneakyThrows;

public class JsonUtils {

  /**
   * Suffix of the temporary files written by {@link #toJson(File, Object)}.
   */
  private static final String TEMP_FILE_SUFFIX = ".json.tmp";

  private static final JsonMapper jsonMapper = new JsonMapper();

  static {
    jsonMapper.registerModule(new Jdk8Module());
    jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  private static final ObjectReader jsonReader = jsonMapper.reader();

  private static final ObjectWriter jsonWriter = jsonMapper.writer();

  @SneakyThrows
  public static <T> T fromJson(String json, Class<T> clazz) {
    return jsonReader.readValue(json, clazz);
  }

  @SneakyThrows
  public static <T> T fromJson(File jsonFile, Class<T> clazz) {
    return jsonReader.readValue(jsonFile, clazz);
  }

  @SneakyThrows
  public static String toJson(Object object) {
    return jsonWriter.writeValueAsString(object);
  }

  /**
   * Write {@code object} as JSON to {@code destFile} atomically. The JSON is written to a temporary
   * file in the same directory, which then replaces {@code destFile}; if the process dies in between,
   * {@code destFile} keeps its previous content.
   */
  @SneakyThrows
  public static void toJson(File destFile, Object object) {
    Path target = destFile.toPath().toAbsolutePath();
    Path temp = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + TEMP_FILE_SUFFIX);
    try {
      try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        jsonWriter.writeValue(out, object);
      }
      PathUtils.moveAtomically(temp, target);
    } catch (Exception e) {
      Files.deleteIfExists(temp);
      throw e;
    }
  }

  /**
   * Delete the temporary files that {@link #toJson(File, Object)} left in {@code directory}
   * because the process died while writing.
   */
  @SneakyThrows
  public static void deleteTempFiles(Path directory) {
    try (DirectoryStream<Path> tempFiles = Files.newDirectoryStream(directory, ".*" + TEMP_FILE_SUFFIX)) {
      for (Path tempFile : tempFiles) {
        Files.deleteIfExists(tempFile);
      }
    }
  }
}
