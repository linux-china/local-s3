package com.robothy.s3.rest;

import com.robothy.s3.core.storage.PersistencePolicy;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

/**
 * The storage settings of a service: the mode it runs in, the data directory it keeps, when its changes reach the
 * disk and how much content it holds. {@linkplain LocalS3Builder#storage(Consumer)} hands out the one that the
 * builder builds its configuration from.
 */
public final class StorageSettings {

    LocalS3Mode mode = LocalS3Mode.IN_MEMORY;

    Path dataPath;

    PersistencePolicy persistencePolicy = PersistencePolicy.DURABLE;

    long maxInMemoryBytes = LocalS3Config.DEFAULT_MAX_IN_MEMORY_BYTES;

    boolean initialDataCacheEnabled = true;

    StorageSettings() {
    }

    /**
     * Set the running mode of the service. Default value is {@code IN_MEMORY}, which holds everything in the Java
     * heap; {@code PERSISTENCE} keeps it in the {@linkplain #dataPath(String) data directory}.
     *
     * @param mode LocalS3 service running mode.
     * @return these settings.
     */
    public StorageSettings mode(@NonNull LocalS3Mode mode) {
        this.mode = mode;
        return this;
    }

    /**
     * Set the data directory of the service. The default value is {@code null}, while data is stored in the Java
     * heap. A {@code PERSISTENCE} service stores everything there; an {@code IN_MEMORY} service reads the
     * directory as the initial data it starts with.
     *
     * @param dataPath data path.
     * @return these settings.
     */
    public StorageSettings dataPath(@NonNull String dataPath) {
        return dataPath(Paths.get(dataPath));
    }

    /**
     * Set the data directory of the service; see {@linkplain #dataPath(String)}.
     *
     * @param dataPath data path.
     * @return these settings.
     */
    public StorageSettings dataPath(@NonNull Path dataPath) {
        this.dataPath = dataPath;
        return this;
    }

    /**
     * Set when the changes of a {@code PERSISTENCE} service reach the disk.
     *
     * <p>{@linkplain PersistencePolicy#DURABLE}, the default, commits the metadata of every change before its
     * request is answered, so a process that is killed loses nothing. A commit writes the file without syncing it
     * to the disk, so it doesn't protect against a power loss. Every commit appends a chunk to the file of the data
     * directory; the requests that change a bucket at the same time share one, but a client that writes one object
     * after the other gets one each: loading twenty thousand objects that way grows the file to about 630 MB for
     * about 2 MB of metadata. The room is reclaimed while the service runs, once most of the file is unused, and
     * when the store is closed.
     *
     * <p>{@linkplain PersistencePolicy#FAST} lets the store commit in the background instead, at most a second
     * after a change, and commits what is left when the service is shut down. The same load then writes about 7 MB
     * and takes about half the time. A killed process loses the changes of the last second, which is the trade a
     * data directory built for a test, or the data of a service embedded in an application or an IDE, can usually
     * make; the Spring Boot starter defaults to it.
     *
     * <p>An {@code IN_MEMORY} service writes nothing, so the policy doesn't apply to it.
     *
     * @param persistencePolicy when the changes reach the disk.
     * @return these settings.
     */
    public StorageSettings persistencePolicy(@NonNull PersistencePolicy persistencePolicy) {
        this.persistencePolicy = persistencePolicy;
        return this;
    }

    /**
     * Set the max number of bytes of heap that the content of an {@code IN_MEMORY} service takes: the objects and
     * the parts of multipart uploads stored in it. Storing content beyond the limit is answered with
     * {@code 507 InsufficientStorage}, whose message suggests the {@code PERSISTENCE} mode, instead of running the
     * JVM that embeds the service, e.g. an application or an IDE, out of heap. The space of deleted objects, and
     * of a {@linkplain LocalS3#reset() reset} service, is available again. The initial data read from the
     * {@linkplain #dataPath(String) data path} doesn't count; its copies are bounded by
     * {@code LOCAL_S3_INITIAL_DATA_CACHE_MAX_BYTES}. A {@code PERSISTENCE} service ignores the limit.
     *
     * <p>Default value is {@linkplain LocalS3Config#DEFAULT_MAX_IN_MEMORY_BYTES}, i.e. a quarter of the max heap;
     * {@code Long.MAX_VALUE} for no limit.
     *
     * @param maxInMemoryBytes max number of bytes, positive.
     * @return these settings.
     * @throws IllegalArgumentException if the number of bytes isn't positive.
     */
    public StorageSettings maxInMemoryBytes(long maxInMemoryBytes) {
        LocalS3Config.requireMaxInMemoryBytes(maxInMemoryBytes);
        this.maxInMemoryBytes = maxInMemoryBytes;
        return this;
    }

    /**
     * This option only available when running LocalS3 in {@code IN_MEMORY} mode
     * with initial data. If initial data cache is enabled, LocalS3 caches the
     * accessed initial data in memory. This could reduce disk I/O when running
     * tests with initial data in the same path.
     *
     * <p> The default value is {@code true}.
     *
     * @param enabled is the initial data cache enabled.
     * @return these settings.
     */
    public StorageSettings initialDataCacheEnabled(boolean enabled) {
        this.initialDataCacheEnabled = enabled;
        return this;
    }

}
