package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.service.BucketGuardApplicable;
import com.robothy.s3.core.model.internal.s3vectors.LocalS3VectorsMetadata;

public interface S3VectorsMetadataAware extends BucketGuardApplicable {

  LocalS3VectorsMetadata metadata();

}
