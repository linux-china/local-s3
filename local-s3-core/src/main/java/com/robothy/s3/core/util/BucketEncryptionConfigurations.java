package com.robothy.s3.core.util;

import com.robothy.s3.core.model.internal.ServerSideEncryption;
import java.io.IOException;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Reads the default encryption of a bucket from the {@code ServerSideEncryptionConfiguration} document that
 * {@code PutBucketEncryption} stored.
 */
public final class BucketEncryptionConfigurations {

  private BucketEncryptionConfigurations() {
  }

  /**
   * The encryption that the objects stored without one get: the {@code ApplyServerSideEncryptionByDefault} of the
   * first {@code Rule}, which is the only rule Amazon S3 allows.
   *
   * <p>The document isn't validated when it is stored, so a document that can't be read, or names an algorithm that
   * LocalS3 doesn't know, applies no default encryption rather than failing every write to the bucket. A
   * {@code KMSMasterKeyID} or a {@code BucketKeyEnabled} is only applied with a KMS algorithm.
   *
   * @param configuration the document; {@code null} if the bucket has none.
   * @return the default encryption; {@code null} for none.
   */
  public static ServerSideEncryption defaultEncryption(String configuration) {
    if (configuration == null || configuration.isBlank()) {
      return null;
    }
    Element rule;
    try {
      rule = firstChild(parse(configuration).getDocumentElement(), "Rule");
    } catch (ParserConfigurationException | SAXException | IOException e) {
      return null;
    }
    Element byDefault = firstChild(rule, "ApplyServerSideEncryptionByDefault");
    String algorithm = text(firstChild(byDefault, "SSEAlgorithm"));
    if (algorithm == null || !ServerSideEncryption.isSupported(algorithm)) {
      return null;
    }
    if (!ServerSideEncryption.isKms(algorithm)) {
      return new ServerSideEncryption(algorithm, null, null, null);
    }
    String bucketKeyEnabled = text(firstChild(rule, "BucketKeyEnabled"));
    return new ServerSideEncryption(algorithm, text(firstChild(byDefault, "KMSMasterKeyID")), null,
        bucketKeyEnabled == null ? null : Boolean.parseBoolean(bucketKeyEnabled));
  }

  private static Document parse(String xml) throws ParserConfigurationException, SAXException, IOException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    // The document is sent by a client; it must not reach out to anything.
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    DocumentBuilder builder = factory.newDocumentBuilder();
    builder.setErrorHandler(null);
    return builder.parse(new InputSource(new StringReader(xml)));
  }

  /**
   * The first child element with a local name, whatever its namespace.
   */
  private static Element firstChild(Element parent, String localName) {
    if (parent == null) {
      return null;
    }
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element && localName.equals(element.getLocalName())) {
        return element;
      }
    }
    return null;
  }

  private static String text(Element element) {
    if (element == null) {
      return null;
    }
    String text = element.getTextContent().trim();
    return text.isEmpty() ? null : text;
  }

}
