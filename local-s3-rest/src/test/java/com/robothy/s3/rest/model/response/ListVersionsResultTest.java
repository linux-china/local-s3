package com.robothy.s3.rest.model.response;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.datatypes.Owner;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import com.robothy.s3.datatypes.response.DeleteMarkerEntry;
import com.robothy.s3.datatypes.response.ObjectVersion;
import com.robothy.s3.rest.utils.XmlUtils;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

class ListVersionsResultTest {

  @Test
  void serialization() throws JacksonException {
    ListVersionsResult listVersionsResult = ListVersionsResult.builder()
        .versions(Arrays.asList(ObjectVersion.builder().versionId("!23123").owner(Owner.DEFAULT_OWNER).build(),
            DeleteMarkerEntry.builder().key("a.txt").owner(Owner.DEFAULT_OWNER).build()))
        .commonPrefixes(List.of(new CommonPrefix("a/"), new CommonPrefix("b/")))
        .build();

    String xml = XmlUtils.createXmlMapper().writeValueAsString(listVersionsResult);
    assertTrue(xml.contains("<VersionId>!23123</VersionId>"), xml);
    assertTrue(xml.contains("<Key>a.txt</Key>"), xml);
  }

  /**
   * A version without a checksum has no {@code ChecksumAlgorithm} element: an empty one is read by the AWS SDK as an
   * algorithm it doesn't know.
   */
  @Test
  void aVersionWithoutAChecksumHasNoChecksumAlgorithm() throws JacksonException {
    String xml = XmlUtils.createXmlMapper().writeValueAsString(ObjectVersion.builder()
        .key("a.txt").versionId("v1").etag("\"etag\"").size(1).build());

    assertFalse(xml.contains("ChecksumAlgorithm"), xml);
    assertFalse(xml.contains("CheckSumAlgorithm"), xml);
  }

  @Test
  void aVersionWithAChecksumNamesItsAlgorithmLikeAmazonS3() throws JacksonException {
    String xml = XmlUtils.createXmlMapper().writeValueAsString(ObjectVersion.builder()
        .key("a.txt").versionId("v1").checkSumAlgorithm(CheckSumAlgorithm.CRC32).build());

    assertTrue(xml.contains("<ChecksumAlgorithm>CRC32</ChecksumAlgorithm>"), xml);
  }

}
