package com.robothy.s3.rest.utils;

import java.util.Set;

/**
 * The XML namespace that Amazon S3 declares on the root element of its documents,
 * {@code http://s3.amazonaws.com/doc/2006-03-01/}, and where LocalS3 declares it.
 *
 * <h2>Why the declaration is added to the document rather than to the models</h2>
 *
 * <p>The obvious way to namespace a document is {@code @JacksonXmlRootElement(namespace = ...)} on the model.
 * That doesn't produce what Amazon S3 answers: Jackson namespaces the root element and then writes
 * {@code xmlns=""} on every child, because the properties of the model declare no namespace of their own.
 *
 * <pre>{@code
 * <ListAllMyBucketsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
 *   <Buckets xmlns="">…</Buckets>            <!-- Jackson: the children are in NO namespace -->
 * </ListAllMyBucketsResult>
 * }</pre>
 *
 * <p>{@code xmlns=""} un-declares the namespace for that element, so the children end up in no namespace at
 * all: worse than the document without a namespace, and precisely wrong for the namespace-aware clients the
 * declaration is for. Writing it on every property of every model instead, so that nothing is un-declared,
 * would mean annotating every field of every response.
 *
 * <p>Amazon S3 declares the namespace once, on the root, and lets the children inherit it. A document that
 * Jackson wrote without any namespace has no {@code xmlns} anywhere, so adding the declaration to its root
 * element gives exactly that: one declaration, inherited by every child. That is what
 * {@linkplain #declareOn(String)} does.
 *
 * <h2>The documents that carry no namespace</h2>
 *
 * <p>An {@code <Error>} of Amazon S3 declares none, so neither does LocalS3's. Every other document is
 * namespaced, which is why this is a list of exceptions rather than a list of the documents to namespace: a
 * response added later is namespaced unless it is named here.
 */
public final class S3XmlNamespace {

  /**
   * The namespace of the Amazon S3 API.
   */
  public static final String URI = "http://s3.amazonaws.com/doc/2006-03-01/";

  /**
   * The root elements that Amazon S3 answers without a namespace.
   */
  private static final Set<String> WITHOUT_NAMESPACE = Set.of("Error");

  private static final String DECLARATION = " xmlns=\"" + URI + "\"";

  private S3XmlNamespace() {
  }

  /**
   * Declare the namespace of Amazon S3 on the root element of a document, unless the document is one that
   * Amazon S3 answers without it, or already declares one.
   *
   * @param xml an XML document, as the mapper of LocalS3 wrote it: no namespace anywhere, and no prolog.
   * @return the document whose root element declares the namespace; {@code xml} if it takes none.
   */
  public static String declareOn(String xml) {
    if (xml == null || xml.isEmpty() || xml.charAt(0) != '<') {
      return xml;
    }
    // The name of the root element ends at the first space, at the "/" of an empty element, or at ">".
    int nameEnd = 1;
    while (nameEnd < xml.length() && !isNameEnd(xml.charAt(nameEnd))) {
      nameEnd++;
    }
    if (nameEnd == 1 || nameEnd == xml.length()) {
      // A prolog, a comment, or a document that was cut short: nothing that a namespace belongs on.
      return xml;
    }
    if (WITHOUT_NAMESPACE.contains(xml.substring(1, nameEnd))) {
      return xml;
    }
    // A document that declares a namespace of its own keeps it, e.g. one that a service wrote as text.
    int rootTagEnd = xml.indexOf('>', nameEnd);
    if (rootTagEnd > 0 && xml.lastIndexOf("xmlns", rootTagEnd) >= nameEnd) {
      return xml;
    }
    return xml.substring(0, nameEnd) + DECLARATION + xml.substring(nameEnd);
  }

  private static boolean isNameEnd(char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '/' || c == '>';
  }

}
