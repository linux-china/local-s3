package com.robothy.s3.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonUtilsTest {

  @TempDir
  Path directory;

  @Test
  void toJsonReplacesFileAndLeavesNoTempFile() throws IOException {
    File file = directory.resolve("a.json").toFile();
    JsonUtils.toJson(file, Map.of("k", "v1"));
    JsonUtils.toJson(file, Map.of("k", "v2"));

    assertEquals(Map.of("k", "v2"), JsonUtils.fromJson(file, Map.class));
    assertEquals(List.of("a.json"), fileNames());
  }

  @Test
  void failedWriteKeepsPreviousContent() throws IOException {
    File file = directory.resolve("a.json").toFile();
    JsonUtils.toJson(file, Map.of("k", "v1"));

    assertThrows(Exception.class, () -> JsonUtils.toJson(file, new Unserializable()));

    assertEquals(Map.of("k", "v1"), JsonUtils.fromJson(file, Map.class));
    assertEquals(List.of("a.json"), fileNames());
  }

  @Test
  void deleteTempFilesRemovesOnlyLeftoverTempFiles() throws IOException {
    Files.writeString(directory.resolve(".a.json.123.json.tmp"), "{\"k\":");
    Files.writeString(directory.resolve("keep.json"), "{}");

    JsonUtils.deleteTempFiles(directory);

    assertEquals(List.of("keep.json"), fileNames());
  }

  private List<String> fileNames() throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      return files.map(path -> path.getFileName().toString()).sorted().collect(Collectors.toList());
    }
  }

  public static class Unserializable {
    public String getValue() {
      throw new IllegalStateException("boom");
    }
  }

}
