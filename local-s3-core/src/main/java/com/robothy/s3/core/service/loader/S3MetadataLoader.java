package com.robothy.s3.core.service.loader;

import com.robothy.s3.core.model.internal.LocalS3Metadata;
import java.nio.file.Path;
import java.util.Objects;

public final class S3MetadataLoader implements MetadataLoader<LocalS3Metadata> {

  @Override
  public LocalS3Metadata load(Path s3DataPath) {
    Objects.requireNonNull(s3DataPath);
    return FileSystemS3MetadataLoader.create().load(s3DataPath);
  }

}
