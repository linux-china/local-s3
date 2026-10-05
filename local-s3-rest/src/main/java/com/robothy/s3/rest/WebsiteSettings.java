package com.robothy.s3.rest;

import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

/**
 * The static website settings of a service; see {@linkplain LocalS3Website}.
 * {@linkplain LocalS3Builder#website(Consumer)} hands out the one that the builder builds its configuration from.
 */
public final class WebsiteSettings {

    LocalS3Website website = LocalS3Website.defaults();

    WebsiteSettings() {
    }

    /**
     * Set whether the buckets are served as static websites to the requests that carry no credentials; see
     * {@linkplain LocalS3Builder#website(boolean)}.
     *
     * @param enabled {@code true} to serve static websites; {@code false} to leave every request to the S3 API.
     * @return these settings.
     */
    public WebsiteSettings enabled(boolean enabled) {
        this.website = this.website.withEnabled(enabled);
        return this;
    }

    /**
     * Serve <b>every</b> bucket as a static website, rather than the public ones alone, which also lets an
     * unsigned request read the objects of a private bucket. It is meant for local development and tests, where
     * publishing a bucket to open a page in a browser is busywork, and is off by default.
     *
     * @param allBuckets {@code true} to serve every bucket without credentials.
     * @return these settings.
     */
    public WebsiteSettings allBuckets(boolean allBuckets) {
        this.website = this.website.withAllBuckets(allBuckets);
        return this;
    }

    /**
     * Set the index document of the buckets that have no {@code WebsiteConfiguration} of their own, e.g.
     * {@code index.html}: the object that a request for a directory is answered with.
     *
     * @param indexDocument the index document.
     * @return these settings.
     * @throws IllegalArgumentException if it is blank.
     */
    public WebsiteSettings indexDocument(@NonNull String indexDocument) {
        this.website = this.website.withIndexDocument(indexDocument);
        return this;
    }

    /**
     * Set the error document of the buckets that have no {@code WebsiteConfiguration} of their own, e.g.
     * {@code error.html}: the object that a request for a key that isn't there is answered with.
     *
     * @param errorDocument the error document; {@code null} answers a generic error page.
     * @return these settings.
     */
    public WebsiteSettings errorDocument(String errorDocument) {
        this.website = this.website.withErrorDocument(errorDocument);
        return this;
    }

    /**
     * Replace the settings with ones the caller holds, e.g. the ones an application read from its own
     * configuration.
     *
     * @param website the settings; {@code null} for the defaults.
     * @return these settings.
     */
    public WebsiteSettings settings(LocalS3Website website) {
        this.website = Objects.requireNonNullElseGet(website, LocalS3Website::defaults);
        return this;
    }

}
