package com.robothy.s3.core.util;

import com.robothy.s3.core.model.internal.ObjectPartMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.storage.CompositeInputStream;
import com.robothy.s3.core.storage.Storage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads and deletes the content of a version of an object, which is either a single stored object that
 * {@linkplain VersionedObjectMetadata#getFileId()} references, or, for a version completed from a multipart upload,
 * the stored objects of its {@linkplain VersionedObjectMetadata#getParts() parts}, one after another.
 */
public final class ObjectContentUtils {

  private ObjectContentUtils() {
  }

  /**
   * Whether the content of a version is the content of its parts.
   *
   * @param version a version that isn't a delete marker.
   * @return {@code true} if the content is stored in the parts of the version.
   */
  public static boolean isComposite(VersionedObjectMetadata version) {
    return Objects.isNull(version.getFileId())
        && version.getParts().map(parts -> !parts.isEmpty()
            && parts.stream().allMatch(part -> Objects.nonNull(part.getFileId()))).orElse(false);
  }

  /**
   * The IDs of the stored objects that hold the content of a version.
   *
   * @param version the version.
   * @return the IDs, in the order of the content; empty for a delete marker.
   */
  public static List<Long> fileIds(VersionedObjectMetadata version) {
    if (version.isDeleted()) {
      return List.of();
    }
    if (isComposite(version)) {
      return version.getParts().orElseThrow().stream().map(ObjectPartMetadata::getFileId).toList();
    }
    return Objects.isNull(version.getFileId()) ? List.of() : List.of(version.getFileId());
  }

  /**
   * Open the whole content of a version.
   *
   * @param storage the storage that holds the content.
   * @param version a version that isn't a delete marker.
   * @return the content.
   */
  public static InputStream open(Storage storage, VersionedObjectMetadata version) {
    if (!isComposite(version)) {
      return storage.getInputStream(version.getFileId());
    }
    return open(storage, version, 0, version.getSize());
  }

  /**
   * Open a region of the content of a version. The stored objects of all parts that the region covers are opened
   * right away, so that the content can be read after the lock that the version was resolved under is released.
   *
   * @param storage the storage that holds the content.
   * @param version a version that isn't a delete marker.
   * @param position the first byte of the region.
   * @param length the number of bytes of the region.
   * @return the content of the region.
   */
  public static InputStream open(Storage storage, VersionedObjectMetadata version, long position, long length) {
    if (!isComposite(version)) {
      return storage.getInputStream(version.getFileId(), position, length);
    }
    if (length == 0) {
      return new ByteArrayInputStream(new byte[0]);
    }

    List<InputStream> streams = new ArrayList<>();
    try {
      long partStart = 0;
      long end = position + length;
      for (ObjectPartMetadata part : version.getParts().orElseThrow()) {
        long partEnd = partStart + part.getSize();
        if (partEnd > position && partStart < end) {
          long from = Math.max(position, partStart) - partStart;
          long to = Math.min(end, partEnd) - partStart;
          streams.add(from == 0 && to == part.getSize()
              ? storage.getInputStream(part.getFileId())
              : storage.getInputStream(part.getFileId(), from, to - from));
        }
        if (partEnd >= end) {
          break;
        }
        partStart = partEnd;
      }
    } catch (RuntimeException e) {
      closeQuietly(streams, e);
      throw e;
    }
    // A region within a single part is the stream of that part, which a transport may transfer as it is.
    return streams.size() == 1 ? streams.get(0) : new CompositeInputStream(streams);
  }

  /**
   * Delete the stored content of a version. Nothing is deleted for a delete marker.
   *
   * @param storage the storage that holds the content.
   * @param version the version whose content is deleted.
   */
  public static void delete(Storage storage, VersionedObjectMetadata version) {
    fileIds(version).forEach(storage::delete);
  }

  private static void closeQuietly(List<InputStream> streams, Throwable cause) {
    for (InputStream stream : streams) {
      try {
        stream.close();
      } catch (IOException e) {
        cause.addSuppressed(e);
      }
    }
  }

}
