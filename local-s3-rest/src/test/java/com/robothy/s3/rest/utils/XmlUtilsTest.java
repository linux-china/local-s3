package com.robothy.s3.rest.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.datatypes.Grant;
import com.robothy.s3.datatypes.Grantee;
import com.robothy.s3.rest.model.response.ListBucketResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The mapper of the XML documents of the service is configured like Jackson 2 configured a mapper, so that the
 * documents stay what they were before LocalS3 moved to Jackson 3.
 */
class XmlUtilsTest {

  @Test
  void writesTheElementsInTheOrderOfTheFieldsOfTheModel() {
    String xml = XmlUtils.toXml(ListBucketResult.builder()
        .isTruncated(false)
        .name("my-bucket")
        .prefix("a/")
        .maxKeys(1000)
        .contents(List.of())
        .build());

    assertTrue(xml.indexOf("<IsTruncated>") < xml.indexOf("<Name>"), xml);
    assertTrue(xml.indexOf("<Name>") < xml.indexOf("<Prefix>"), xml);
    assertTrue(xml.indexOf("<Prefix>") < xml.indexOf("<MaxKeys>"), xml);
  }

  /**
   * Jackson 3 detects {@code xsi:type} by default and declares its namespace, which the {@code xmlns:xsi} attribute of
   * a grantee declares already; a document that declares it twice isn't well-formed.
   */
  @Test
  void declaresTheXsiNamespaceOfAGranteeOnce() {
    Grantee grantee = new Grantee();
    grantee.setId("123");
    grantee.setType("CanonicalUser");
    Grant grant = new Grant();
    grant.setGrantee(grantee);
    grant.setPermission("FULL_CONTROL");
    AccessControlPolicy policy = AccessControlPolicy.builder().grants(List.of(grant)).build();

    String xml = XmlUtils.toXml(policy);

    assertEquals(xml.indexOf("xmlns:xsi="), xml.lastIndexOf("xmlns:xsi="), xml);
    assertTrue(xml.contains("xsi:type=\"CanonicalUser\""), xml);
    assertEquals(policy, XmlUtils.fromXml(xml, AccessControlPolicy.class));
  }

}
