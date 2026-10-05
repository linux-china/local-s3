package com.robothy.s3.rest;

import java.nio.file.Path;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

/**
 * The HTTPS settings of a service: the certificate it serves, and whether it serves HTTPS alone.
 * {@linkplain LocalS3Builder#tls(Consumer)} hands out the one that the builder builds its configuration from.
 */
public final class TlsSettings {

    LocalS3Tls tls;

    boolean required = false;

    TlsSettings() {
    }

    /**
     * Serve HTTPS with a certificate and its private key in PEM format, each given either as PEM content or as
     * the path of a PEM file; see {@linkplain LocalS3Builder#tls(String, String)} for where such a pair comes
     * from and what the port then answers.
     *
     * @param certPem the certificate chain, as PEM content or as the path of a PEM file.
     * @param keyPem the unencrypted PKCS#8 private key, as PEM content or as the path of a PEM file.
     * @return these settings.
     * @throws IllegalArgumentException if a file can't be read, or the certificate and key are invalid.
     */
    public TlsSettings certificate(@NonNull String certPem, @NonNull String keyPem) {
        this.tls = LocalS3Tls.of(certPem, keyPem);
        return this;
    }

    /**
     * Serve HTTPS with the certificate and private key of PEM files; see
     * {@linkplain LocalS3Builder#tls(String, String)}.
     *
     * @param certPemFile the PEM file of the certificate chain.
     * @param keyPemFile the PEM file of the unencrypted PKCS#8 private key.
     * @return these settings.
     * @throws IllegalArgumentException if a file can't be read, or the certificate and key are invalid.
     */
    public TlsSettings certificate(@NonNull Path certPemFile, @NonNull Path keyPemFile) {
        return certificate(certPemFile.toString(), keyPemFile.toString());
    }

    /**
     * Serve HTTPS with a certificate and private key that the caller holds; see
     * {@linkplain LocalS3Builder#tls(LocalS3Tls)}.
     *
     * @param tls the certificate and its private key.
     * @return these settings.
     */
    public TlsSettings certificate(@NonNull LocalS3Tls tls) {
        this.tls = tls;
        return this;
    }

    /**
     * Serve HTTPS with a certificate generated for {@code localhost}, {@code 127.0.0.1} and {@code ::1}, i.e.
     * {@code certificate(LocalS3Tls.selfSigned())}. A client that uses HTTPS by default then connects to a local
     * service without a certificate of the machine, once it is given the certificate or told not to verify it.
     *
     * @return these settings.
     * @throws IllegalStateException if the JVM generates neither an EC nor an RSA key pair.
     */
    public TlsSettings selfSigned() {
        return certificate(LocalS3Tls.selfSigned());
    }

    /**
     * Serve HTTPS with a certificate generated for the given host names and IP addresses, e.g. the name a
     * container is reached by; see {@linkplain LocalS3Tls#selfSigned(String...)}.
     *
     * @param hosts the host names and IP addresses to issue the certificate for, at least one.
     * @return these settings.
     * @throws IllegalArgumentException if {@code hosts} is empty, or a host is blank or an invalid IP address.
     * @throws IllegalStateException if the JVM generates neither an EC nor an RSA key pair.
     */
    public TlsSettings selfSigned(String... hosts) {
        return certificate(LocalS3Tls.selfSigned(hosts));
    }

    /**
     * Serve HTTPS alone on the port, instead of answering both HTTP and HTTPS on it. A plain HTTP request to the
     * service then fails, which is what a test asserts that its client really uses TLS with.
     *
     * <p>A service with a certificate accepts both by default, since a port that answers whatever a client speaks
     * is one less thing to configure. Without a certificate this has no effect: there is nothing to serve HTTPS
     * with.
     *
     * @param required {@code true} to refuse plain HTTP; {@code false}, the default, to answer HTTP and HTTPS on
     *     the same port.
     * @return these settings.
     */
    public TlsSettings required(boolean required) {
        this.required = required;
        return this;
    }

}
