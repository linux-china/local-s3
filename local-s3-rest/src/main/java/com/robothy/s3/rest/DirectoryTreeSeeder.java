package com.robothy.s3.rest;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seeds the {@code <bucket>/<key>} tree of a directory, or of a classpath location; see
 * {@code docs/embedding.md#seed-a-directory-tree}.
 */
final class DirectoryTreeSeeder implements LocalS3Seeder {

  private static final Logger log = LoggerFactory.getLogger(DirectoryTreeSeeder.class);

  private final String description;

  private final RootsSupplier roots;

  private DirectoryTreeSeeder(String description, RootsSupplier roots) {
    this.description = description;
    this.roots = roots;
  }

  static DirectoryTreeSeeder directory(Path directory) {
    return new DirectoryTreeSeeder(directory.toString(), (fileSystems, out) -> {
      if (Files.isDirectory(directory)) {
        out.add(directory);
      }
    });
  }

  static DirectoryTreeSeeder classpath(String location, ClassLoader classLoader) {
    String normalized = normalize(location);
    return new DirectoryTreeSeeder("classpath:" + normalized, (fileSystems, out) -> {
      // Every classpath entry that holds the location, like classpath*: of Spring, so that the fixtures of a jar of
      // shared test fixtures are seeded too.
      for (URL url : Collections.list(classLoader.getResources(normalized))) {
        Path root = toPath(url, fileSystems);
        if (root != null && Files.isDirectory(root)) {
          out.add(root);
        }
      }
    });
  }

  @Override
  public void seed(Fixtures fixtures) throws IOException {
    List<FileSystem> fileSystems = new ArrayList<>();
    try {
      List<Path> resolved = new ArrayList<>();
      roots.collect(fileSystems, resolved);
      // A typo in a test annotation would otherwise start the service without its fixtures, failing the test far
      // from the cause.
      if (resolved.isEmpty()) {
        throw new FileNotFoundException("No directory " + description + " to seed LocalS3 from.");
      }
      for (Path root : resolved) {
        seed(root, fixtures);
      }
    } finally {
      for (FileSystem fileSystem : fileSystems) {
        fileSystem.close();
      }
    }
  }

  private void seed(Path root, Fixtures fixtures) throws IOException {
    List<Path> files;
    try (Stream<Path> walk = Files.walk(root)) {
      // Sorted, so that the objects of a key that several roots hold are put in a stable order.
      files = walk.filter(Files::isRegularFile).sorted().toList();
    }
    for (Path file : files) {
      Path relative = root.relativize(file);
      if (relative.getNameCount() < 2) {
        log.warn("Skipped the fixture {} of {}: a file directly in the tree names no bucket.", relative, description);
        continue;
      }
      String bucketName = relative.getName(0).toString();
      String key = relative.subpath(1, relative.getNameCount()).toString().replace(root.getFileSystem()
          .getSeparator(), "/");
      try (InputStream content = Files.newInputStream(file)) {
        fixtures.object(bucketName, key, content, Files.size(file), URLConnection.guessContentTypeFromName(key));
      }
    }
  }

  private static Path toPath(URL url, List<FileSystem> fileSystems) throws IOException {
    try {
      return switch (url.getProtocol()) {
        case "file" -> Path.of(url.toURI());
        case "jar" -> {
          JarURLConnection connection = (JarURLConnection) url.openConnection();
          // A file system of its own rather than the shared one of the jar URI, which another caller may close.
          FileSystem fileSystem = FileSystems.newFileSystem(Path.of(connection.getJarFileURL().toURI()));
          fileSystems.add(fileSystem);
          yield fileSystem.getPath("/" + connection.getEntryName());
        }
        default -> {
          log.warn("Skipped the classpath entry {}: only directories and jar files can be seeded from.", url);
          yield null;
        }
      };
    } catch (URISyntaxException e) {
      throw new IOException("Invalid classpath URL " + url, e);
    }
  }

  private static String normalize(String location) {
    String normalized = location.strip();
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    if (normalized.isEmpty()) {
      // The root of the classpath would seed every resource, classes included.
      throw new IllegalArgumentException("A classpath seed must name a directory of the classpath, e.g. s3-fixtures, "
          + "not the classpath root.");
    }
    return normalized;
  }

  @FunctionalInterface
  private interface RootsSupplier {
    void collect(List<FileSystem> fileSystems, List<Path> roots) throws IOException;
  }

  @Override
  public String toString() {
    return "DirectoryTreeSeeder[" + description + "]";
  }

}
