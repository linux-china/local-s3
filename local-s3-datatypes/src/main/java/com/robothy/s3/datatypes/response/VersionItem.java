package com.robothy.s3.datatypes.response;

/**
 * An entry of the versions of a {@code ListObjectVersions} answer: a version of an object, or a delete marker.
 */
public sealed interface VersionItem permits ObjectVersion, DeleteMarkerEntry {

}
