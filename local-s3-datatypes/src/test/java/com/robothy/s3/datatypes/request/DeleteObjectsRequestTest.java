package com.robothy.s3.datatypes.request;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.robothy.s3.datatypes.ObjectIdentifier;
import org.junit.jupiter.api.Test;

class DeleteObjectsRequestTest {

  @Test
  void testDeserializeDeleteObjectsRequest() throws JsonProcessingException {

    String xml = "<Delete>\n" +
        "  <Object>\n" +
        "    <Key>a.txt</Key>\n" +
        "    <VersionId>null</VersionId>\n" +
        "  </Object>\n" +
        "  <Object>\n" +
        "    <Key>b.txt</Key>\n" +
        "  </Object>\n" +
        "  <Quiet>true</Quiet>\n" +
        "</Delete>";

    XmlMapper xmlMapper = new XmlMapper();
    DeleteObjectsRequest request = xmlMapper.readValue(xml, DeleteObjectsRequest.class);
    assertEquals(2, request.getObjects().size());
    ObjectIdentifier objectIdentifier1 = request.getObjects().get(0);
    assertEquals("a.txt", objectIdentifier1.getKey());
    assertTrue(objectIdentifier1.getVersionId().isPresent());
    assertEquals("null", objectIdentifier1.getVersionId().get());

    ObjectIdentifier objectIdentifier2 = request.getObjects().get(1);
    assertEquals("b.txt", objectIdentifier2.getKey());
    assertTrue(objectIdentifier2.getVersionId().isEmpty());

    assertTrue(request.isQuiet());
  }

  @Test
  void deserializesTheConditionsOfAnObject() throws JsonProcessingException {
    String xml = "<Delete>\n" +
        "  <Object>\n" +
        "    <Key>a.txt</Key>\n" +
        "    <ETag>\"5d41402abc4b2a76b9719d911017c592\"</ETag>\n" +
        "    <LastModifiedTime>2026-09-14T10:15:30.000Z</LastModifiedTime>\n" +
        "    <Size>5</Size>\n" +
        "  </Object>\n" +
        "  <Object>\n" +
        "    <Key>b.txt</Key>\n" +
        "  </Object>\n" +
        "</Delete>";

    // The service reads the request with the Java time module, like this.
    XmlMapper xmlMapper = new XmlMapper();
    xmlMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    DeleteObjectsRequest request = xmlMapper.readValue(xml, DeleteObjectsRequest.class);

    ObjectIdentifier conditional = request.getObjects().get(0);
    assertEquals("\"5d41402abc4b2a76b9719d911017c592\"", conditional.getETag());
    assertEquals(java.time.Instant.parse("2026-09-14T10:15:30Z"), conditional.getLastModifiedTime());
    assertEquals(5L, conditional.getSize());

    ObjectIdentifier unconditional = request.getObjects().get(1);
    assertNull(unconditional.getETag());
    assertNull(unconditional.getLastModifiedTime());
    assertNull(unconditional.getSize());
  }

}