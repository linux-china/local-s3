package com.robothy.s3.core.model.answers;

import com.robothy.s3.core.model.internal.ObjectChecksum;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import com.robothy.s3.datatypes.enums.ChecksumType;
import com.robothy.s3.datatypes.enums.StorageClass;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class ListPartsAns {

  private String bucket;

  private String key;

  private String uploadId;

  private Integer partNumberMarker;

  private Integer nextPartNumberMarker;

  private Integer maxParts;

  private boolean isTruncated;

  private List<Part> parts;

  /**
   * The algorithm of the checksum of the upload; {@code null} for none.
   */
  private CheckSumAlgorithm checksumAlgorithm;

  private ChecksumType checksumType;

  /**
   * The storage class that the upload stores the object with.
   */
  private StorageClass storageClass;

  @Data
  @Builder
  public static class Part {

    private Integer partNumber;

    private long lastModified;

    private String eTag;

    private long size;

    /**
     * The checksum that the part was uploaded with; {@code null} for none.
     */
    private ObjectChecksum checksum;

  }

}
