package com.robothy.s3.core.service.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.service.ObjectService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The data path of a LocalS3 before 2.5 kept every object file directly in the storage directory. A persistent
 * service moves the files into their subdirectories; an in-memory service reads them where they are.
 */
class StorageLayoutTest {

  private static final String BUCKET = "my-bucket";

  @Test
  void aPersistentServiceMovesTheObjectFilesOfAnOlderDataPath(@TempDir Path dataPath) throws IOException {
    writeObjectsInTheFlatLayout(dataPath);

    ObjectService objectService = LocalS3Manager.createFileSystemS3Manager(dataPath).objectService();

    assertEquals("Hello", read(objectService, "a.txt"));
    assertEquals("World", read(objectService, "b.txt"));
    assertEquals(List.of(), flatObjectFiles(dataPath), "No object file is left in the flat layout.");
    assertEquals(2, objectFiles(dataPath).size());
  }

  @Test
  void anInMemoryServiceReadsAnOlderDataPathWithoutChangingIt(@TempDir Path dataPath) throws IOException {
    writeObjectsInTheFlatLayout(dataPath);
    List<Path> flat = flatObjectFiles(dataPath);

    for (boolean cached : new boolean[] {true, false}) {
      ObjectService objectService = new InMemoryLocalS3Manager(dataPath, cached, new InitialDataCache(16, 1024))
          .objectService();
      assertEquals("Hello", read(objectService, "a.txt"));
      assertEquals("World", read(objectService, "b.txt"));
      objectService.deleteObject(BUCKET, "a.txt");
    }

    assertEquals(flat, flatObjectFiles(dataPath), "The initial data isn't changed.");
  }

  /**
   * Store objects with a persistent service, and move their files to where a LocalS3 before 2.5 kept them.
   */
  private static void writeObjectsInTheFlatLayout(Path dataPath) throws IOException {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    manager.bucketService().createBucket(BUCKET);
    put(manager.objectService(), "a.txt", "Hello");
    put(manager.objectService(), "b.txt", "World");
    Path storage = dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY);
    for (Path file : objectFiles(dataPath)) {
      Files.move(file, storage.resolve(file.getFileName()));
    }
    assertEquals(2, flatObjectFiles(dataPath).size());
    assertTrue(objectFiles(dataPath).stream().allMatch(file -> file.getParent().equals(storage)));
  }

  private static void put(ObjectService objectService, String key, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    objectService.putObject(BUCKET, key, PutObjectOptions.builder()
        .content(new ByteArrayInputStream(bytes)).size(bytes.length).build());
  }

  private static String read(ObjectService objectService, String key) throws IOException {
    try (InputStream in = objectService.getObject(BUCKET, key, GetObjectOptions.builder().build()).getContent()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static List<Path> objectFiles(Path dataPath) throws IOException {
    try (Stream<Path> files = Files.walk(dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY))) {
      return files.filter(Files::isRegularFile).sorted().toList();
    }
  }

  private static List<Path> flatObjectFiles(Path dataPath) throws IOException {
    try (Stream<Path> files = Files.list(dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY))) {
      return files.filter(Files::isRegularFile).sorted().toList();
    }
  }

}
