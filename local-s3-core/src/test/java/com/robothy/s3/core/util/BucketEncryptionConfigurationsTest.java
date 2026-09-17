package com.robothy.s3.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ServerSideEncryption;
import org.junit.jupiter.api.Test;

class BucketEncryptionConfigurationsTest {

  private static String configuration(String byDefault, String rule) {
    return "<ServerSideEncryptionConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Rule>"
        + "<ApplyServerSideEncryptionByDefault>" + byDefault + "</ApplyServerSideEncryptionByDefault>" + rule
        + "</Rule></ServerSideEncryptionConfiguration>";
  }

  @Test
  void readsSseS3AndIgnoresKmsSettings() {
    assertEquals(new ServerSideEncryption("AES256", null, null, null),
        BucketEncryptionConfigurations.defaultEncryption(configuration(
            "<SSEAlgorithm>AES256</SSEAlgorithm><KMSMasterKeyID>key</KMSMasterKeyID>",
            "<BucketKeyEnabled>true</BucketKeyEnabled>")));
  }

  @Test
  void readsSseKms() {
    assertEquals(new ServerSideEncryption("aws:kms", "arn:key", null, true),
        BucketEncryptionConfigurations.defaultEncryption(configuration(
            "<SSEAlgorithm>aws:kms</SSEAlgorithm><KMSMasterKeyID> arn:key </KMSMasterKeyID>",
            "<BucketKeyEnabled>true</BucketKeyEnabled>")));
    assertEquals(new ServerSideEncryption("aws:kms:dsse", null, null, null),
        BucketEncryptionConfigurations.defaultEncryption("<ServerSideEncryptionConfiguration><Rule>"
            + "<ApplyServerSideEncryptionByDefault><SSEAlgorithm>aws:kms:dsse</SSEAlgorithm>"
            + "</ApplyServerSideEncryptionByDefault></Rule></ServerSideEncryptionConfiguration>"));
  }

  @Test
  void appliesNothingForWhatItCannotRead() {
    assertNull(BucketEncryptionConfigurations.defaultEncryption(null));
    assertNull(BucketEncryptionConfigurations.defaultEncryption("Bucket Encryption"));
    assertNull(BucketEncryptionConfigurations.defaultEncryption(configuration("<SSEAlgorithm>AES128</SSEAlgorithm>", "")));
    assertNull(BucketEncryptionConfigurations.defaultEncryption("<ServerSideEncryptionConfiguration/>"));
    assertNull(BucketEncryptionConfigurations.defaultEncryption("<!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
        + "<ServerSideEncryptionConfiguration><Rule><ApplyServerSideEncryptionByDefault><SSEAlgorithm>&x;"
        + "</SSEAlgorithm></ApplyServerSideEncryptionByDefault></Rule></ServerSideEncryptionConfiguration>"));
  }

  @Test
  void bucketMetadataFollowsItsConfiguration() {
    BucketMetadata bucket = new BucketMetadata();
    assertNull(bucket.getDefaultEncryption());
    bucket.setEncryption(configuration("<SSEAlgorithm>AES256</SSEAlgorithm>", ""));
    assertEquals("AES256", bucket.getDefaultEncryption().algorithm());
    bucket.setEncryption(configuration("<SSEAlgorithm>aws:kms</SSEAlgorithm>", ""));
    assertEquals("aws:kms", bucket.getDefaultEncryption().algorithm());
    bucket.setEncryption(null);
    assertNull(bucket.getDefaultEncryption());
  }

}
