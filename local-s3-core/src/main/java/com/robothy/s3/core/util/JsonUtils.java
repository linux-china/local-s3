package com.robothy.s3.core.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Reads and writes JSON. A failure to read or write reaches the caller as an {@linkplain UncheckedIOException},
 * so that the callers of the stores built on this, e.g. the metadata stores, see the path that fails.
 */
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

  /**
   * @throws UncheckedIOException if {@code json} cannot be read as a {@code T}.
   */
  public static <T> T fromJson(String json, Class<T> clazz) {
    try {
      return jsonReader.readValue(json, clazz);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read a " + clazz.getSimpleName() + " from JSON.", e);
    }
  }

  /**
   * @throws UncheckedIOException if {@code jsonFile} cannot be read as a {@code T}.
   */
  public static <T> T fromJson(File jsonFile, Class<T> clazz) {
    try {
      return jsonReader.readValue(jsonFile, clazz);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read a " + clazz.getSimpleName() + " from " + jsonFile + ".", e);
    }
  }

  /**
   * @throws UncheckedIOException if {@code object} cannot be written as JSON.
   */
  public static String toJson(Object object) {
    try {
      return jsonWriter.writeValueAsString(object);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write " + object.getClass().getSimpleName() + " as JSON.", e);
    }
  }

  /**
   * Write {@code object} as JSON to {@code destFile} atomically. The JSON is written to a temporary
   * file in the same directory, which then replaces {@code destFile}; if the process dies in between,
   * {@code destFile} keeps its previous content.
   *
   * @throws UncheckedIOException if {@code object} cannot be written to {@code destFile}.
   */
  public static void toJson(File destFile, Object object) {
    Path target = destFile.toPath().toAbsolutePath();
    Path temp = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + TEMP_FILE_SUFFIX);
    try {
      try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        jsonWriter.writeValue(out, object);
      }
      PathUtils.moveAtomically(temp, target);
    } catch (IOException e) {
      deleteQuietly(temp, e);
      throw new UncheckedIOException("Failed to write " + target + ".", e);
    } catch (RuntimeException e) {
      deleteQuietly(temp, e);
      throw e;
    }
  }

  /**
   * Delete the temporary file that a failed write left behind. A failure to delete it is reported with the
   * failure of the write, rather than in place of it.
   */
  private static void deleteQuietly(Path file, Throwable cause) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      cause.addSuppressed(e);
    }
  }

  /**
   * Delete the temporary files that {@link #toJson(File, Object)} left in {@code directory}
   * because the process died while writing.
   *
   * @throws UncheckedIOException if {@code directory} cannot be read.
   */
  public static void deleteTempFiles(Path directory) {
    try (DirectoryStream<Path> tempFiles = Files.newDirectoryStream(directory, ".*" + TEMP_FILE_SUFFIX)) {
      for (Path tempFile : tempFiles) {
        Files.deleteIfExists(tempFile);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to delete the temporary files in " + directory + ".", e);
    }
  }
}
