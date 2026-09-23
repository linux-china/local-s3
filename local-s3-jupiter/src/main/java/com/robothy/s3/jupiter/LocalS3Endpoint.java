package com.robothy.s3.jupiter;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Represent the endpoint information of a LocalS3 service.
 */
public class LocalS3Endpoint {

    private final int port;

    private final String endpoint;

    private final String region;

    private String accessKey;

    private String secretKey;

    public LocalS3Endpoint(int port) {
        this.port = port;
        this.region = "local";
        this.endpoint = "http://127.0.0.1:" + port;
    }

    public int port() {
        return port;
    }

    public String endpoint() {
        return endpoint;
    }

    public String region() {
        return region;
    }

    /**
     * The URI of the Iceberg REST catalog of the service, which a {@code RESTCatalog} is initialized with:
     *
     * <pre>{@code
     *  catalog.initialize("local", Map.of("uri", endpoint.icebergCatalogUri()));
     * }</pre>
     *
     * <p>Only a service started with {@linkplain LocalS3#icebergCatalog()} answers it; on any other service the
     * catalog requests are 404s, since the path is then an ordinary bucket path.
     *
     * @return the catalog URI, i.e. the endpoint followed by {@code /iceberg}.
     */
    public String icebergCatalogUri() {
        return endpoint + "/iceberg";
    }

    /**
     * The endpoint that a client of the S3 Tables API of the service is given.
     *
     * <p>A signed client — one built with the credentials of a service that verifies them — is pointed at
     * {@linkplain #endpoint()} itself: it signs for the {@code s3tables} service, which is what the service tells a
     * request of that API from an Amazon S3 one by. An unsigned client carries no such scope, so it is pointed at the
     * path that the API also answers under, {@code /s3tables}.
     *
     * @param signed whether the client signs its requests.
     * @return the endpoint of the S3 Tables API.
     */
    public String s3TablesEndpoint(boolean signed) {
        return signed ? endpoint : endpoint + "/s3tables";
    }

    @Nullable
    public String accessKey() {
        return accessKey;
    }

    @Nullable
    public String secretKey() {
        return secretKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LocalS3Endpoint that = (LocalS3Endpoint) o;
        return port == that.port && Objects.equals(endpoint, that.endpoint) && Objects.equals(region, that.region);
    }

    @Override
    public int hashCode() {
        return Objects.hash(port, endpoint, region);
    }
}
