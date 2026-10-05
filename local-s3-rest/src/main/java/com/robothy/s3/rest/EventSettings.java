package com.robothy.s3.rest;

import com.robothy.s3.core.event.S3ChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

/**
 * The settings of the {@linkplain com.robothy.s3.core.event.S3Change changes} that a service publishes: the
 * listeners that receive them, and the executor that delivers them.
 * {@linkplain LocalS3Builder#events(Consumer)} hands out the one that the builder builds its configuration from.
 */
public final class EventSettings {

    final List<S3ChangeListener> listeners = new ArrayList<>();

    Executor executor = Runnable::run;

    EventSettings() {
    }

    /**
     * Subscribe a listener to the {@linkplain com.robothy.s3.core.event.S3Change changes} that the services
     * commit: buckets created and deleted, objects created and deleted, object tagging and ACLs changed, and
     * multipart uploads aborted. The changes are delivered however the services are called, by an HTTP request or
     * directly through {@linkplain LocalS3#getS3Manager()}. Several listeners may be subscribed; each receives
     * every change.
     *
     * @param listener receives the committed changes.
     * @return these settings.
     */
    public EventSettings listener(@NonNull S3ChangeListener listener) {
        this.listeners.add(Objects.requireNonNull(listener));
        return this;
    }

    /**
     * Set the executor that delivers the changes to the {@linkplain #listener(S3ChangeListener) listeners}.
     *
     * <p>By default, listeners run synchronously on the thread handling the request, so a change is
     * delivered before the S3 response is sent. Pass an executor, e.g.
     * {@code Executors.newSingleThreadExecutor()}, to deliver changes asynchronously so that slow listeners
     * don't hold up request handling; a single-threaded executor keeps the changes in order. LocalS3 does not
     * shut the executor down.
     *
     * <p>Either way, an exception thrown by a listener is logged and does not fail the S3 request.
     *
     * @param executor executor that runs the change listeners.
     * @return these settings.
     */
    public EventSettings executor(@NonNull Executor executor) {
        this.executor = Objects.requireNonNull(executor);
        return this;
    }

}
