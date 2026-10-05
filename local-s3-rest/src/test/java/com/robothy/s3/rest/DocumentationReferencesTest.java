package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the references to {@code docs/*.md} resolvable, from the code and between the documents.
 *
 * <p>The behavior of LocalS3 is specified once, in the documents, and the code links to the section that specifies
 * what it implements, e.g. {@code docs/semantics.md#conditional-requests}, rather than restating it. A renamed heading
 * would otherwise leave the code pointing at nothing, and nobody would notice.
 */
class DocumentationReferencesTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

  private static final Path DOCS = ROOT.resolve("docs");

  /** A reference of the code, e.g. {@code docs/semantics.md#conditional-requests}; the anchor is optional. */
  private static final Pattern CODE_REFERENCE = Pattern.compile("docs/([a-z0-9-]+\\.md)(?:#([a-z0-9-]+))?");

  /** A relative Markdown link of a document, e.g. {@code [semantics](semantics.md#versioning)} or {@code (#modules)}. */
  private static final Pattern DOCUMENT_LINK = Pattern.compile("\\]\\((?!https?:|mailto:)([^)\\s]*?)(?:#([^)\\s]+))?\\)");

  private static final Pattern HEADING = Pattern.compile("^#{1,6} (.+)$", Pattern.MULTILINE);

  private static final Pattern FENCE = Pattern.compile("^```.*?^```", Pattern.MULTILINE | Pattern.DOTALL);

  private final Map<Path, Set<String>> anchors = new HashMap<>();

  @Test
  void referencesOfTheCodeResolve() throws IOException {
    List<String> broken = new ArrayList<>();
    try (Stream<Path> files = Files.walk(ROOT)) {
      files.filter(file -> file.toString().endsWith(".java"))
          .filter(file -> file.toString().contains("/src/"))
          .forEach(file -> read(file).lines().filter(DocumentationReferencesTest::isComment).forEach(line -> {
            Matcher matcher = CODE_REFERENCE.matcher(line);
            while (matcher.find()) {
              check(DOCS.resolve(matcher.group(1)), matcher.group(2), ROOT.relativize(file), matcher.group(), broken);
            }
          }));
    }
    assertTrue(broken.isEmpty(), "Broken references to the documents:\n" + String.join("\n", broken));
  }

  @Test
  void linksBetweenTheDocumentsResolve() throws IOException {
    List<String> broken = new ArrayList<>();
    List<Path> documents = new ArrayList<>();
    documents.add(ROOT.resolve("README.md"));
    try (Stream<Path> files = Files.list(DOCS)) {
      files.filter(file -> file.toString().endsWith(".md")).sorted().forEach(documents::add);
    }
    for (Path document : documents) {
      Matcher matcher = DOCUMENT_LINK.matcher(FENCE.matcher(read(document)).replaceAll(""));
      while (matcher.find()) {
        String target = matcher.group(1);
        if (target.isEmpty() || target.endsWith(".md")) {
          Path file = target.isEmpty() ? document : document.getParent().resolve(target).normalize();
          check(file, matcher.group(2), ROOT.relativize(document), matcher.group(), broken);
        }
      }
    }
    assertTrue(broken.isEmpty(), "Broken links between the documents:\n" + String.join("\n", broken));
  }

  private void check(Path document, String anchor, Path from, String reference, List<String> broken) {
    if (!Files.isRegularFile(document)) {
      broken.add(from + ": " + reference + " (no such document)");
    } else if (anchor != null && !anchorsOf(document).contains(anchor)) {
      broken.add(from + ": " + reference + " (no such heading)");
    }
  }

  private Set<String> anchorsOf(Path document) {
    return anchors.computeIfAbsent(document, file -> {
      Set<String> found = new HashSet<>();
      Map<String, Integer> repeats = new HashMap<>();
      Matcher matcher = HEADING.matcher(FENCE.matcher(read(file)).replaceAll(""));
      while (matcher.find()) {
        String slug = slug(matcher.group(1));
        int repeat = repeats.merge(slug, 1, Integer::sum) - 1;
        found.add(repeat == 0 ? slug : slug + "-" + repeat);
      }
      return found;
    });
  }

  /** Only comments reference the documents; a string, e.g. an object key of a test, doesn't. */
  private static boolean isComment(String line) {
    String code = line.strip();
    return code.startsWith("*") || code.startsWith("//") || code.startsWith("/*");
  }

  /** The anchor that GitHub gives a heading: lower case, punctuation dropped, spaces as hyphens. */
  static String slug(String heading) {
    return heading.strip().toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N} _-]", "")
        .replace(' ', '-');
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

}
