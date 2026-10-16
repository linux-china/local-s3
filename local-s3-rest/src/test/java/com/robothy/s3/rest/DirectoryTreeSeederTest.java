package com.robothy.s3.rest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryTreeSeederTest {

  @Test
  void seedsEveryClasspathEntryThatHoldsTheLocation(@TempDir Path dir) throws Exception {
    Path jar = dir.resolve("fixtures.jar");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      // With directory entries, like the jars of Gradle and Maven: a class loader finds a directory by its entry.
      for (String directory : List.of("fixtures/", "fixtures/s3/", "fixtures/s3/uploads/")) {
        out.putNextEntry(new JarEntry(directory));
        out.closeEntry();
      }
      out.putNextEntry(new JarEntry("fixtures/s3/uploads/from-jar.txt"));
      out.write("jar".getBytes(UTF_8));
      out.closeEntry();
    }
    Path classes = dir.resolve("classes");
    Files.createDirectories(classes.resolve("fixtures/s3/uploads/nested"));
    Files.writeString(classes.resolve("fixtures/s3/uploads/nested/from-dir.txt"), "dir");
    Files.writeString(classes.resolve("fixtures/s3/README.md"), "no bucket");

    List<String> seeded = new ArrayList<>();
    try (URLClassLoader classLoader = new URLClassLoader(new URL[] {classes.toUri().toURL(), jar.toUri().toURL()},
        null)) {
      DirectoryTreeSeeder.classpath("/fixtures/s3/", classLoader).seed(recording(seeded));
    }
    assertEquals(List.of("uploads/nested/from-dir.txt=dir", "uploads/from-jar.txt=jar"), seeded);
  }

  @Test
  void failsOnMissingDirectory(@TempDir Path dir) {
    assertThrows(java.io.FileNotFoundException.class,
        () -> LocalS3Seeder.directory(dir.resolve("missing")).seed(recording(new ArrayList<>())));
  }

  private static LocalS3Seeder.Fixtures recording(List<String> seeded) {
    return new LocalS3Seeder.Fixtures() {
      @Override
      public void bucket(String bucketName) {
      }

      @Override
      public void object(String bucketName, String key, byte[] content) {
      }

      @Override
      public void object(String bucketName, String key, Path file) {
      }

      @Override
      public void object(String bucketName, String key, java.io.InputStream content, long size, String contentType)
          throws java.io.IOException {
        try (content) {
          seeded.add(bucketName + "/" + key + "=" + new String(content.readAllBytes(), UTF_8));
        }
      }
    };
  }

}
