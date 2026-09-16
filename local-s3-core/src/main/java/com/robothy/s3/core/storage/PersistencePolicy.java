package com.robothy.s3.core.storage;

/**
 * When a {@code PERSISTENCE} service writes the metadata it changed to the file of its data directory.
 *
 * <p>The metadata store of a service always writes a change into the key-value store, so every request sees it
 * whatever the policy is. What the policy decides is when those changes reach the disk, which is a trade-off between
 * what a killed process loses and what writing costs.
 */
public enum PersistencePolicy {

  /**
   * Commit the metadata of every change, so that a process that is killed loses nothing that a request was answered
   * for. This is the default.
   *
   * <p>A commit appends a chunk to the file, so a burst of small writes leaves a chunk each: the file of a bulk load
   * grows far beyond the metadata it holds, and the space is only reclaimed once the chunks fall out of the retention
   * time of MVStore. The file is compacted when the store is closed, so what a data directory rests at is the
   * metadata it holds, but a bulk load needs the room while it runs. Use {@linkplain #FAST} for those.
   */
  DURABLE,

  /**
   * Let the store commit in the background, at most a second after a change, and commit what is left when the service
   * shuts down.
   *
   * <p>A killed process loses the changes of the last second. In exchange a burst of small writes is committed
   * together rather than one chunk each, which is both much quicker and far smaller on disk: loading twenty thousand
   * objects writes about 5 MB instead of about 420 MB, and takes about a tenth of the time.
   *
   * <p>Meant for what LocalS3 is for: setting up a data directory to test against, e.g. the table of a big data
   * engine, where the data is built again if it is lost. A service that is shut down, rather than killed, persists
   * everything either way.
   */
  FAST

}
