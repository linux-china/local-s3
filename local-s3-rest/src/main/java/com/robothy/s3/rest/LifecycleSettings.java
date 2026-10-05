package com.robothy.s3.rest;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * The settings of when a service applies the lifecycle configurations of its buckets by itself.
 * {@linkplain LocalS3Builder#lifecycle(Consumer)} hands out the one that the builder builds its configuration from.
 */
public final class LifecycleSettings {

    Duration interval;

    LifecycleSettings() {
    }

    /**
     * Apply the lifecycle configurations of the buckets once the service has started, and then every
     * {@code interval} after a run ends, like {@linkplain LocalS3#applyLifecycle(java.time.Instant)} at the current
     * time; see {@code docs/semantics.md#lifecycle-configuration}.
     *
     * @param interval the time between two runs; {@code null} or zero to never apply them, which is the default.
     * @return these settings.
     * @throws IllegalArgumentException if {@code interval} is negative.
     */
    public LifecycleSettings applyEvery(Duration interval) {
        LocalS3Config.requireLifecycleInterval(interval);
        this.interval = interval;
        return this;
    }

}
