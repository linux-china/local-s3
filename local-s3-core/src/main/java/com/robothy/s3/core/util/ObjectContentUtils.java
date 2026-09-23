package com.robothy.s3.core.util;

import com.robothy.s3.core.model.internal.ObjectPartMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.storage.CompositeInputStream;
import com.robothy.s3.core.storage.ContentRetention;
import com.robothy.s3.core.storage.Storage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
   * Open a region of the content of a version. A region of several parts is read part by part, and each part is
   * opened when it is read, so that a whole object doesn't hold a file open per part; its content is
   * {@linkplain Storage#retain(java.util.Collection) retained} instead, so that the parts are still there when they
   * are read, even if the object is overwritten or deleted in the meantime. A storage that doesn't retain content
   * opens every part of the region right away, as before.
   *
   * <p>Either way, the content can be read after the lock that the version was resolved under is released.
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

    List<Region> regions = regions(version, position, length);
    // A region within a single part is the stream of that part, which a transport may transfer as it is.
    if (regions.size() == 1) {
      return regions.get(0).open(storage);
    }

    Optional<ContentRetention> retention = storage.retain(regions.stream().map(Region::fileId).toList());
    if (retention.isEmpty()) {
      return openEagerly(storage, regions);
    }
    try {
      return new CompositeInputStream(regions.stream()
          .map(region -> (CompositeInputStream.Part) () -> region.open(storage))
          .toList(), retention.get());
    } catch (RuntimeException e) {
      retention.get().close();
      throw e;
    }
  }

  /**
   * The regions of the parts that the region {@code [position, position + length)} of the content covers, in the
   * order of the content.
   */
  private static List<Region> regions(VersionedObjectMetadata version, long position, long length) {
    List<Region> regions = new ArrayList<>();
    long partStart = 0;
    long end = position + length;
    for (ObjectPartMetadata part : version.getParts().orElseThrow()) {
      long partEnd = partStart + part.getSize();
      if (partEnd > position && partStart < end) {
        long from = Math.max(position, partStart) - partStart;
        long to = Math.min(end, partEnd) - partStart;
        regions.add(new Region(part.getFileId(), from, to - from, part.getSize()));
      }
      if (partEnd >= end) {
        break;
      }
      partStart = partEnd;
    }
    return regions;
  }

  /**
   * Open every part of a region right away, for a storage that doesn't retain content: a part that was opened while
   * the version was resolved stays readable even if the content is deleted afterwards.
   */
  private static InputStream openEagerly(Storage storage, List<Region> regions) {
    List<InputStream> streams = new ArrayList<>(regions.size());
    try {
      for (Region region : regions) {
        streams.add(region.open(storage));
      }
    } catch (RuntimeException e) {
      closeQuietly(streams, e);
      throw e;
    }
    return new CompositeInputStream(streams);
  }

  /**
   * Delete the stored content of a version. Nothing is deleted for a delete marker. Content that is being read is
   * deleted once the last reader of it is done, see {@linkplain Storage#retain(java.util.Collection)}.
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

  /**
   * The region of a part that the read covers.
   *
   * @param fileId the ID of the stored object that holds the content of the part.
   * @param from the first byte of the region within the part.
   * @param length the number of bytes of the region.
   * @param partSize the number of bytes of the whole part.
   */
  private record Region(Long fileId, long from, long length, long partSize) {

    InputStream open(Storage storage) {
      return from == 0 && length == partSize ? storage.getInputStream(fileId)
          : storage.getInputStream(fileId, from, length);
    }
  }

}
