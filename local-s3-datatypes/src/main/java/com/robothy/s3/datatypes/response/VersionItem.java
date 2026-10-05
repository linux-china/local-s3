package com.robothy.s3.datatypes.response;

/**
 * An entry of the versions of a {@code ListObjectVersions} answer: a version of an object, or a delete marker.
 */
public sealed interface VersionItem permits ObjectVersion, DeleteMarkerEntry {

  String key();

  /**
   * A copy of the entry with another key, e.g. the key encoded for {@code encoding-type=url}.
   */
  VersionItem withKey(String key);

}
