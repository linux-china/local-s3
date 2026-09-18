package com.robothy.s3.core.iceberg;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The identifier of a table or a view of the Iceberg catalog: the levels of its namespace, and its name.
 *
 * <p>A namespace has several levels, e.g. {@code db.schema}, and a level may hold any character, {@code .} included,
 * so the levels are kept apart rather than joined: the REST API passes them in a path as one segment separated by the
 * unit separator {@code 0x1F}, see {@linkplain #parseNamespace}, and this class keeps them as a list.
 *
 * @param namespace the levels of the namespace, which is empty for the root namespace.
 * @param name the name of the table or the view.
 */
public record IcebergIdentifier(List<String> namespace, String name) {

  /**
   * The character that separates the levels of a namespace in a path of the REST API, and in the keys of the
   * catalog store. It is the unit separator, which the REST catalog specification chose because a name may hold
   * any printable character.
   */
  public static final char SEPARATOR = '';

  /**
   * The character that separates the namespace of a table from its name in the keys of the catalog store. It is the
   * record separator, so that the tables of a namespace are the keys that start with the key of that namespace
   * followed by this character: the namespaces {@code a} and {@code a.b} then have keys that no prefix of the one
   * confuses with a table of the other.
   */
  static final char NAME_SEPARATOR = '';

  public IcebergIdentifier {
    namespace = List.copyOf(Objects.requireNonNull(namespace, "namespace"));
    Objects.requireNonNull(name, "name");
  }

  /**
   * Create an identifier.
   *
   * @param namespace the levels of the namespace.
   * @param name the name of the table or the view.
   * @return the identifier.
   */
  public static IcebergIdentifier of(List<String> namespace, String name) {
    return new IcebergIdentifier(namespace, name);
  }

  /**
   * Read the levels of a namespace from a path segment of the REST API, which separates them with
   * {@value #SEPARATOR}, e.g. {@code db} or {@code dbschema}. The segment is already URL-decoded.
   *
   * @param segment the segment; {@code null} or empty for the root namespace.
   * @return the levels; empty for the root namespace.
   */
  public static List<String> parseNamespace(String segment) {
    if (segment == null || segment.isEmpty()) {
      return List.of();
    }
    List<String> levels = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < segment.length(); i++) {
      if (segment.charAt(i) == SEPARATOR) {
        levels.add(segment.substring(start, i));
        start = i + 1;
      }
    }
    levels.add(segment.substring(start));
    return List.copyOf(levels);
  }

  /**
   * The key that the catalog store keeps a namespace under: its levels, separated by {@value #SEPARATOR}. The root
   * namespace has the empty key.
   *
   * @param namespace the levels of the namespace.
   * @return the key.
   */
  public static String namespaceKey(List<String> namespace) {
    return String.join(String.valueOf(SEPARATOR), namespace);
  }

  /**
   * A namespace as a client reads it, e.g. {@code db.schema}.
   *
   * @param namespace the levels of the namespace.
   * @return the namespace, with its levels separated by {@code .}.
   */
  public static String toString(List<String> namespace) {
    return String.join(".", namespace);
  }

  /**
   * The key that the catalog store keeps this table or view under.
   *
   * @return the key of its namespace, {@value #NAME_SEPARATOR} and its name.
   */
  public String key() {
    return namespaceKey(namespace) + NAME_SEPARATOR + name;
  }

  /**
   * The prefix of the keys of the tables and views of a namespace, which no table of another namespace has.
   *
   * @param namespace the levels of the namespace.
   * @return the prefix.
   */
  static String tablePrefix(List<String> namespace) {
    return namespaceKey(namespace) + NAME_SEPARATOR;
  }

  /**
   * The identifier as a client reads it, e.g. {@code db.schema.orders}.
   */
  @Override
  public String toString() {
    return namespace.isEmpty() ? name : toString(namespace) + "." + name;
  }

}
