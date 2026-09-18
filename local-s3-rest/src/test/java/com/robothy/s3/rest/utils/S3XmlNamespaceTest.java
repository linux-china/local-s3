package com.robothy.s3.rest.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.robothy.s3.datatypes.response.S3Error;
import com.robothy.s3.rest.model.response.ListAllMyBucketsResult;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

class S3XmlNamespaceTest {

  @Test
  void declaresTheNamespaceOnTheRootElement() {
    assertEquals("<Result xmlns=\"" + S3XmlNamespace.URI + "\"><A>1</A></Result>",
        S3XmlNamespace.declareOn("<Result><A>1</A></Result>"));
  }

  @Test
  void declaresItOnAnEmptyRootElementAndOneWithAttributes() {
    assertEquals("<Result xmlns=\"" + S3XmlNamespace.URI + "\"/>", S3XmlNamespace.declareOn("<Result/>"));
    assertEquals("<Result xmlns=\"" + S3XmlNamespace.URI + "\" a=\"1\"><A>1</A></Result>",
        S3XmlNamespace.declareOn("<Result a=\"1\"><A>1</A></Result>"));
  }

  @Test
  void leavesTheDocumentsThatAmazonS3AnswersWithoutANamespaceAlone() {
    String error = "<Error><Code>NoSuchKey</Code></Error>";
    assertSame(error, S3XmlNamespace.declareOn(error));
  }

  @Test
  void declaresNothingTwice() {
    String declared = "<Result xmlns=\"http://example.org\"><A>1</A></Result>";
    assertSame(declared, S3XmlNamespace.declareOn(declared));
  }

  @Test
  void leavesAloneWhatIsNoDocument() {
    assertNull(S3XmlNamespace.declareOn(null));
    assertEquals("", S3XmlNamespace.declareOn(""));
    assertEquals("not xml", S3XmlNamespace.declareOn("not xml"));
    assertEquals("<", S3XmlNamespace.declareOn("<"));
    assertEquals("<Result", S3XmlNamespace.declareOn("<Result"));
  }

  /**
   * What the declaration is for: a namespace-aware parser resolves the root <em>and every child</em> in the
   * namespace of Amazon S3. Jackson's own {@code @JacksonXmlRootElement(namespace = ...)} would instead write
   * {@code xmlns=""} on the children and leave them in no namespace; see {@linkplain S3XmlNamespace}.
   */
  @Test
  void aNamespaceAwareParserResolvesTheChildrenInTheNamespaceToo() throws Exception {
    ListAllMyBucketsResult result = new ListAllMyBucketsResult();
    result.setContinuationToken("next-page");

    Document document = parseNamespaceAware(XmlUtils.toXml(result));

    Node root = document.getDocumentElement();
    assertEquals(S3XmlNamespace.URI, root.getNamespaceURI());
    assertEquals("ListAllMyBucketsResult", root.getLocalName());
    Node child = root.getFirstChild();
    assertEquals(S3XmlNamespace.URI, child.getNamespaceURI(), "the children inherit the namespace of the root");
  }

  @Test
  void anErrorIsParsedInNoNamespace() throws Exception {
    String xml = XmlUtils.toXml(S3Error.builder().code("NoSuchKey").message("The specified key does not exist.")
        .key("a.txt").build());
    assertFalse(xml.contains("xmlns"), xml);

    Node root = parseNamespaceAware(xml).getDocumentElement();
    assertNull(root.getNamespaceURI());
    assertEquals("Error", root.getNodeName());
  }

  private static Document parseNamespaceAware(String xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    return factory.newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

}
