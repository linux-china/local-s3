package com.robothy.s3.core.model.internal;

import com.robothy.s3.datatypes.enums.StorageClass;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingMetadata.html#SysMetadata">system-defined
 * metadata</a> of an object that the client sets when it stores the object, besides its {@code Content-Type}, and
 * that the object is served with. Each value is kept as the client sent it, e.g. {@code Expires} as an HTTP date.
 *
 * <p>An object that was stored without any of them, including one stored by a LocalS3 before 2.5, has no
 * {@code SystemMetadata} at all rather than an empty one.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SystemMetadata {

  private String cacheControl;

  private String contentDisposition;

  private String contentEncoding;

  private String contentLanguage;

  private String expires;

  /**
   * The {@code x-amz-storage-class} that the object was stored with; {@code null} for
   * {@linkplain StorageClass#STANDARD}. LocalS3 keeps it only to answer it: an object of any storage class is read
   * like a {@code STANDARD} one, e.g. a {@code GLACIER} one needs no restore.
   */
  private StorageClass storageClass;

  /**
   * The {@code x-amz-website-redirect-location} that the object was stored with: a path in the same bucket, e.g.
   * {@code /docs/index.html}, or an {@code http://} or {@code https://} URL. {@code GetObject} and {@code HeadObject}
   * answer it, and the static website endpoint redirects a request for the object to it.
   */
  private String websiteRedirectLocation;

  /**
   * The storage class of an object.
   *
   * @param systemMetadata the system-defined metadata of the object; {@code null} if it has none.
   * @return the storage class; {@linkplain StorageClass#STANDARD} if it was stored without one.
   */
  public static StorageClass storageClassOf(SystemMetadata systemMetadata) {
    return Objects.isNull(systemMetadata) || Objects.isNull(systemMetadata.getStorageClass())
        ? StorageClass.STANDARD : systemMetadata.getStorageClass();
  }

  /**
   * The system-defined metadata with another storage class.
   *
   * @param systemMetadata the system-defined metadata; {@code null} for none.
   * @param storageClass the storage class; {@code null} for {@linkplain StorageClass#STANDARD}.
   * @return a copy with the storage class; {@code null} if it carries nothing else.
   */
  public static SystemMetadata withStorageClass(SystemMetadata systemMetadata, StorageClass storageClass) {
    SystemMetadata result = Objects.isNull(systemMetadata) ? new SystemMetadata()
        : systemMetadata.toBuilder().build();
    result.setStorageClass(storageClass == StorageClass.STANDARD ? null : storageClass);
    return result.equals(new SystemMetadata()) ? null : result;
  }

  /**
   * The system-defined metadata with another website redirect location.
   *
   * @param systemMetadata the system-defined metadata; {@code null} for none.
   * @param websiteRedirectLocation the website redirect location; {@code null} for none.
   * @return a copy with the website redirect location; {@code null} if it carries nothing else.
   */
  public static SystemMetadata withWebsiteRedirectLocation(SystemMetadata systemMetadata,
                                                           String websiteRedirectLocation) {
    SystemMetadata result = Objects.isNull(systemMetadata) ? new SystemMetadata()
        : systemMetadata.toBuilder().build();
    result.setWebsiteRedirectLocation(websiteRedirectLocation);
    return result.equals(new SystemMetadata()) ? null : result;
  }

}
