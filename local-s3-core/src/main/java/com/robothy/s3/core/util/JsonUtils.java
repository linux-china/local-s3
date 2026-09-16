package com.robothy.s3.core.util;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.util.TokenBuffer;

/**
 * Reads and writes JSON. A failure to read or write reaches the caller as an {@linkplain UncheckedIOException},
 * so that the callers of the stores built on this, e.g. the metadata stores, see the path that fails.
 */
public class JsonUtils {

  /**
   * Suffix of the temporary files written by {@link #toJson(File, Object)}.
   */
  private static final String TEMP_FILE_SUFFIX = ".json.tmp";

  /**
   * Configured like Jackson 2 configured a mapper, so that the JSON of the stored metadata stays what it was, e.g. its
   * properties in the order of the fields rather than sorted. Optional values are supported by Jackson 3 itself.
   */
  private static final JsonMapper jsonMapper = JsonMapper.builderWithJackson2Defaults()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .build();

  private static final ObjectWriter jsonWriter = jsonMapper.writer();

  /**
   * @throws UncheckedIOException if {@code json} cannot be read as a {@code T}.
   */
  public static <T> T fromJson(String json, Class<T> clazz) {
    try {
      return jsonMapper.readValue(json, clazz);
    } catch (JacksonException e) {
      throw unchecked("Failed to read a " + clazz.getSimpleName() + " from JSON.", e);
    }
  }

  /**
   * @throws UncheckedIOException if {@code jsonFile} cannot be read as a {@code T}.
   */
  public static <T> T fromJson(File jsonFile, Class<T> clazz) {
    try {
      return jsonMapper.readValue(jsonFile, clazz);
    } catch (JacksonException e) {
      throw unchecked("Failed to read a " + clazz.getSimpleName() + " from " + jsonFile + ".", e);
    }
  }

  /**
   * Write an object as JSON tokens, which {@linkplain #fromTokens} reads as a new object as often as needed, without
   * encoding the JSON as text. Copying an object this way is about twice as fast as a round trip through a string.
   *
   * @param object the object to write.
   * @return the tokens of the object.
   */
  public static TokenBuffer toTokens(Object object) {
    TokenBuffer tokens = TokenBuffer.forGeneration();
    try {
      jsonMapper.writeValue(tokens, object);
    } catch (JacksonException e) {
      throw unchecked("Failed to write " + object.getClass().getSimpleName() + " as JSON tokens.", e);
    }
    return tokens;
  }

  /**
   * Read a new object from the tokens that {@linkplain #toTokens} wrote.
   *
   * @param tokens the tokens.
   * @param clazz the type of the object.
   * @return a new object.
   */
  public static <T> T fromTokens(TokenBuffer tokens, Class<T> clazz) {
    try (JsonParser parser = tokens.asParser()) {
      return jsonMapper.readValue(parser, clazz);
    } catch (JacksonException e) {
      throw unchecked("Failed to read a " + clazz.getSimpleName() + " from JSON tokens.", e);
    }
  }

  /**
   * @throws UncheckedIOException if {@code object} cannot be written as JSON.
   */
  public static String toJson(Object object) {
    try {
      return jsonWriter.writeValueAsString(object);
    } catch (JacksonException e) {
      throw unchecked("Failed to write " + object.getClass().getSimpleName() + " as JSON.", e);
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
    } catch (JacksonException e) {
      deleteQuietly(temp, e);
      throw unchecked("Failed to write " + target + ".", e);
    } catch (RuntimeException e) {
      deleteQuietly(temp, e);
      throw e;
    }
  }

  /**
   * The failure of Jackson, whose exceptions are unchecked and not {@linkplain IOException}s since Jackson 3, as the
   * {@linkplain UncheckedIOException} that the callers of this class expect: with the I/O failure that Jackson wrapped,
   * e.g. a file that doesn't exist, or with the failure of Jackson itself, e.g. malformed JSON.
   */
  private static UncheckedIOException unchecked(String message, JacksonException e) {
    IOException cause = e.getCause() instanceof IOException io ? io : new IOException(e.getMessage(), e);
    return new UncheckedIOException(message, cause);
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
