package com.robothy.s3.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LifecycleRuleTest {

  @Test
  void readsTheRulesOfAConfiguration() {
    List<LifecycleRule> rules = LifecycleRule.parse("""
        <LifecycleConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
          <Rule>
            <ID>legacy</ID>
            <Prefix>tmp/</Prefix>
            <Status>Enabled</Status>
            <Expiration><Date>2030-01-01T00:00:00Z</Date></Expiration>
          </Rule>
          <Rule>
            <ID>filtered</ID>
            <Filter>
              <And>
                <Prefix>logs/</Prefix>
                <Tag><Key>tier</Key><Value>cold</Value></Tag>
                <ObjectSizeGreaterThan>10</ObjectSizeGreaterThan>
              </And>
            </Filter>
            <Status>Disabled</Status>
            <Transition><Days>30</Days><StorageClass>GLACIER</StorageClass></Transition>
            <NoncurrentVersionExpiration>
              <NoncurrentDays>7</NoncurrentDays>
              <NewerNoncurrentVersions>2</NewerNoncurrentVersions>
            </NoncurrentVersionExpiration>
            <AbortIncompleteMultipartUpload><DaysAfterInitiation>3</DaysAfterInitiation></AbortIncompleteMultipartUpload>
          </Rule>
        </LifecycleConfiguration>
        """);

    assertEquals(2, rules.size());
    LifecycleRule legacy = rules.get(0);
    assertTrue(legacy.enabled());
    assertEquals("tmp/", legacy.filter().prefix());
    assertEquals(Instant.parse("2030-01-01T00:00:00Z").toEpochMilli(), legacy.expirationDate());
    assertNull(legacy.expirationDays());

    LifecycleRule filtered = rules.get(1);
    assertFalse(filtered.enabled());
    assertEquals(new LifecycleRule.Filter("logs/", Map.of("tier", "cold"), 10L, null), filtered.filter());
    assertEquals(7, filtered.noncurrentDays());
    assertEquals(2, filtered.newerNoncurrentVersions());
    assertEquals(3, filtered.abortIncompleteMultipartUploadDays());

    assertTrue(filtered.filter().matches("logs/a", new String[][] {{"tier", "cold"}}, 11));
    assertFalse(filtered.filter().matches("logs/a", new String[][] {{"tier", "hot"}}, 11));
    assertFalse(filtered.filter().matches("logs/a", new String[][] {{"tier", "cold"}}, 10));
  }

  /**
   * Amazon S3 adds the days to the time and rounds up to the next midnight UTC.
   */
  @Test
  void roundsUpToTheNextMidnight() {
    long created = Instant.parse("2014-01-15T10:30:00Z").toEpochMilli();
    assertEquals(Instant.parse("2014-01-19T00:00:00Z").toEpochMilli(), LifecycleRule.dueAt(created, 3));
    long midnight = Instant.parse("2014-01-15T00:00:00Z").toEpochMilli();
    assertEquals(Instant.parse("2014-01-16T00:00:00Z").toEpochMilli(), LifecycleRule.dueAt(midnight, 1));
  }

}
