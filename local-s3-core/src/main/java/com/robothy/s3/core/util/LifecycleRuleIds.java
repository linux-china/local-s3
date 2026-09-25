package com.robothy.s3.core.util;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Gives the rules of a {@code LifecycleConfiguration} that have no {@code ID} a generated one, like Amazon S3 does
 * when a configuration is put.
 */
public final class LifecycleRuleIds {

  private LifecycleRuleIds() {
  }

  /**
   * Add a generated {@code ID}, as the first child element, to every {@code Rule} that has none.
   *
   * @param configuration a {@code LifecycleConfiguration} document that was validated.
   * @return the document with an {@code ID} in every rule.
   * @throws IllegalArgumentException if the document can't be read.
   */
  public static String withGeneratedIds(String configuration) {
    try {
      Document document = parse(configuration);
      for (Node node = document.getDocumentElement().getFirstChild(); node != null; node = node.getNextSibling()) {
        if (node instanceof Element rule && "Rule".equals(rule.getLocalName()) && !hasChild(rule, "ID")) {
          String prefix = rule.getPrefix();
          Element id = document.createElementNS(rule.getNamespaceURI(), prefix == null ? "ID" : prefix + ":ID");
          id.setTextContent(generateId());
          rule.insertBefore(id, rule.getFirstChild());
        }
      }
      Transformer transformer = TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
      StringWriter writer = new StringWriter();
      transformer.transform(new DOMSource(document), new StreamResult(writer));
      // The declaration of the document, as it was put, if it has one.
      String trimmed = configuration.stripLeading();
      int declarationEnd = trimmed.startsWith("<?xml") ? trimmed.indexOf("?>") : -1;
      return declarationEnd < 0 ? writer.toString() : trimmed.substring(0, declarationEnd + 2) + "\n" + writer;
    } catch (ParserConfigurationException | SAXException | IOException | TransformerException e) {
      throw new IllegalArgumentException("Failed to read the lifecycle configuration.", e);
    }
  }

  /**
   * An ID like the ones Amazon S3 generates: the Base64 of a random UUID.
   */
  private static String generateId() {
    return Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII));
  }

  private static boolean hasChild(Element parent, String localName) {
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element && localName.equals(element.getLocalName())) {
        return true;
      }
    }
    return false;
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

}
