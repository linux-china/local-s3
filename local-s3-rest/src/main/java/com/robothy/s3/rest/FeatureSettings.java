package com.robothy.s3.rest;

import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

/**
 * The optional capabilities of a service, all on by default; see {@linkplain LocalS3Features}.
 * {@linkplain LocalS3Builder#features(Consumer)} hands out the one that the builder builds its configuration from.
 */
public final class FeatureSettings {

    LocalS3Features features = LocalS3Features.all();

    FeatureSettings() {
    }

    /**
     * Set whether the S3 Vectors API is served.
     *
     * @param enabled {@code false} to leave its routes out.
     * @return these settings.
     */
    public FeatureSettings vector(boolean enabled) {
        this.features = this.features.withVector(enabled);
        return this;
    }

    /**
     * Set whether the S3 Tables API, and the Iceberg REST catalog of its table buckets, is served.
     *
     * @param enabled {@code false} to leave its routes out.
     * @return these settings.
     */
    public FeatureSettings s3Tables(boolean enabled) {
        this.features = this.features.withS3Tables(enabled);
        return this;
    }

    /**
     * Set whether the AWS KMS endpoint is served.
     *
     * @param enabled {@code false} to leave its routes out.
     * @return these settings.
     */
    public FeatureSettings kms(boolean enabled) {
        this.features = this.features.withKms(enabled);
        return this;
    }

    /**
     * Set whether the AWS STS endpoint is served.
     *
     * @param enabled {@code false} to leave its routes out.
     * @return these settings.
     */
    public FeatureSettings sts(boolean enabled) {
        this.features = this.features.withSts(enabled);
        return this;
    }

    /**
     * Set whether the built-in console is served under {@code /_admin/ui}.
     *
     * @param enabled {@code false} to leave its routes out.
     * @return these settings.
     */
    public FeatureSettings console(boolean enabled) {
        this.features = this.features.withConsole(enabled);
        return this;
    }

    /**
     * Replace the settings with ones the caller holds, e.g. the ones an application read from its own configuration.
     *
     * @param features the features.
     * @return these settings.
     */
    public FeatureSettings settings(@NonNull LocalS3Features features) {
        this.features = Objects.requireNonNull(features, "features");
        return this;
    }

}
