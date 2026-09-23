package com.robothy.s3.core.storage;

/**
 * When a {@code PERSISTENCE} service writes the metadata it changed to the file of its data directory.
 *
 * <p>The metadata store of a service always writes a change into the key-value store, so every request sees it
 * whatever the policy is. What the policy decides is when those changes are written to the file, which is a trade-off
 * between what a killed process loses and what writing costs. Neither policy syncs the file to the disk on a commit,
 * so neither protects against a power loss; see {@linkplain #DURABLE}.
 */
public enum PersistencePolicy {

  /**
   * Commit the metadata of every change before its request is answered, so that a process that is killed, or that
   * crashes, loses nothing that a request was answered for. This is the default of {@code LocalS3Builder} and of the
   * standalone service; the Spring Boot starter defaults to {@linkplain #FAST}.
   *
   * <p>It is not a sync to the disk: a commit writes the metadata to the file without an {@code fsync}, so the
   * operating system holds it until it writes it back. A process that is killed loses nothing, but a machine that
   * loses power, or an operating system that crashes, may lose the last commits.
   *
   * <p>A commit appends a chunk to the file. The requests that change a bucket at the same time share a commit, since
   * each commits once the lock of the bucket is released, but a client that writes one object after the other gets a
   * commit each: the file of such a bulk load grows far beyond the metadata it holds. The room of the chunks that
   * later commits supersede is reclaimed while the service runs, once most of the file is such room, and when the
   * store is closed. A bulk load still needs the room while it runs; use {@linkplain #FAST} for those.
   */
  DURABLE,

  /**
   * Let the store commit in the background, at most a second after a change, and commit what is left when the service
   * shuts down.
   *
   * <p>A killed process loses the changes of the last second. In exchange a burst of small writes is committed
   * together rather than one chunk each, which is both much quicker and far smaller on disk: loading twenty thousand
   * objects one after the other writes about 7 MB instead of about 630 MB, and takes about half the time.
   *
   * <p>Meant for what LocalS3 is for: setting up a data directory to test against, e.g. the table of a big data
   * engine, or the data of a service embedded in an application or an IDE, where the data is built again if it is
   * lost. A service that is shut down, rather than killed, persists everything either way.
   */
  FAST

}
