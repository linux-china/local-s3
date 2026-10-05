package com.robothy.s3.rest;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The default CORS settings of a service; see {@linkplain LocalS3Cors}.
 * {@linkplain LocalS3Builder#defaultCors(Consumer)} hands out the one that the builder builds its configuration from.
 */
public final class CorsSettings {

    LocalS3Cors cors = LocalS3Cors.disabled();

    CorsSettings() {
    }

    /**
     * Set the origins that are allowed, e.g. {@code http://localhost:5173}; each may contain one {@code *}
     * wildcard, and {@code *} alone allows every origin. No origin turns the default rule off.
     *
     * @param origins the origins.
     * @return these settings.
     */
    public CorsSettings allowedOrigins(String... origins) {
        this.cors = this.cors.withAllowedOrigins(List.of(origins));
        return this;
    }

    /**
     * Set the methods that are allowed, among {@code GET}, {@code PUT}, {@code POST}, {@code DELETE} and
     * {@code HEAD}; none, the default, allows all of them.
     *
     * @param methods the methods.
     * @return these settings.
     * @throws IllegalArgumentException if a method isn't one of them.
     */
    public CorsSettings allowedMethods(String... methods) {
        this.cors = this.cors.withAllowedMethods(List.of(methods));
        return this;
    }

    /**
     * Set the request headers that are allowed, each may contain one {@code *} wildcard; none, the default, allows
     * every header.
     *
     * @param headers the headers.
     * @return these settings.
     */
    public CorsSettings allowedHeaders(String... headers) {
        this.cors = this.cors.withAllowedHeaders(List.of(headers));
        return this;
    }

    /**
     * Set the response headers that the pages may read; none, the default, exposes
     * {@linkplain LocalS3Cors#DEFAULT_EXPOSE_HEADERS}.
     *
     * @param headers the headers.
     * @return these settings.
     */
    public CorsSettings exposeHeaders(String... headers) {
        this.cors = this.cors.withExposeHeaders(List.of(headers));
        return this;
    }

    /**
     * Set the seconds that a browser may cache a preflight response.
     *
     * @param maxAgeSeconds the seconds; {@code null} for the browser's default.
     * @return these settings.
     */
    public CorsSettings maxAgeSeconds(Integer maxAgeSeconds) {
        this.cors = this.cors.withMaxAgeSeconds(maxAgeSeconds);
        return this;
    }

    /**
     * Replace the settings with ones the caller holds, e.g. the ones an application read from its own
     * configuration.
     *
     * @param cors the settings; {@code null} for none.
     * @return these settings.
     */
    public CorsSettings settings(LocalS3Cors cors) {
        this.cors = Objects.requireNonNullElseGet(cors, LocalS3Cors::disabled);
        return this;
    }

}
