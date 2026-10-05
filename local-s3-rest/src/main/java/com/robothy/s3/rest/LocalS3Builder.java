package com.robothy.s3.rest;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.event.S3ChangeListener;
import com.robothy.s3.core.exception.InvalidBucketNameException;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds {@linkplain LocalS3} instances, and the {@linkplain LocalS3Config} they are created from. A builder can build
 * several instances; changing it afterwards doesn't affect the instances already built.
 *
 * <p>The settings a service is usually built with — its address, its mode and data directory, its buckets and its
 * credentials — are methods of the builder itself. The settings of a domain that only some services tune are grouped
 * behind one method per domain, which takes the settings of that domain and applies them:
 *
 * <pre>{@code
 *  LocalS3 s3 = LocalS3.builder()
 *      .port(29090)
 *      .mode(LocalS3Mode.PERSISTENCE)
 *      .dataPath("/tmp/local-s3")
 *      .credentials("local-s3", "local-s3-secret")
 *      .netty(netty -> netty.childEventGroupThreadNum(8).maxRequestBodySize(64 * 1024 * 1024))
 *      .tls(tls -> tls.selfSigned().required(true))
 *      .website(website -> website.allBuckets(true).indexDocument("home.html"))
 *      .icebergCatalog(iceberg -> iceberg.warehouse("s3://lakehouse/"))
 *      .build();
 * }</pre>
 *
 * <ul>
 *   <li>{@linkplain #storage(Consumer)} — the mode, the data directory and what the storage holds,
 *       {@linkplain StorageSettings};</li>
 *   <li>{@linkplain #netty(Consumer)} — the threads of the HTTP server, the limits of a request and the shutdown
 *       hook, {@linkplain NettySettings};</li>
 *   <li>{@linkplain #s3Api(Consumer)} — how the S3 API itself answers: the domains of virtual-hosted-style requests
 *       and the entity tags of multipart uploads, {@linkplain S3ApiSettings};</li>
 *   <li>{@linkplain #events(Consumer)} — the listeners of the committed changes and the executor that delivers them,
 *       {@linkplain EventSettings};</li>
 *   <li>{@linkplain #tls(Consumer)} — the certificate of HTTPS and whether HTTP is refused,
 *       {@linkplain TlsSettings};</li>
 *   <li>{@linkplain #website(Consumer)} — the static website hosting, {@linkplain WebsiteSettings};</li>
 *   <li>{@linkplain #defaultCors(Consumer)} — the CORS rule of the buckets without one of their own,
 *       {@linkplain CorsSettings};</li>
 *   <li>{@linkplain #icebergCatalog(Consumer)} — the Iceberg REST catalog, {@linkplain IcebergCatalogSettings}.</li>
 * </ul>
 *
 * <p>Each domain also has the one-liner that turns it on, e.g. {@linkplain #tls(LocalS3Tls)},
 * {@linkplain #website(boolean)} and {@linkplain #icebergCatalog(boolean)}, for a service that takes its defaults.
 */
public class LocalS3Builder {

    private static final Logger log = LoggerFactory.getLogger(LocalS3.class);

    /**
     * Create a builder with the defaults of an embedded service; {@linkplain LocalS3#builder()} is the usual way.
     */
    public LocalS3Builder() {
    }

    private String bindHost = "127.0.0.1";

    private int port = 29090;

    private final List<String> defaultBuckets = new ArrayList<>();

    private final List<String> versionedBuckets = new ArrayList<>();

    private final List<LocalS3Seeder> seeders = new ArrayList<>();

    private String accessKeyId;

    private String secretAccessKey;

    private final StorageSettings storageSettings = new StorageSettings();

    private final NettySettings nettySettings = new NettySettings();

    private final S3ApiSettings s3ApiSettings = new S3ApiSettings();

    private final EventSettings eventSettings = new EventSettings();

    private final LifecycleSettings lifecycleSettings = new LifecycleSettings();

    private final TlsSettings tlsSettings = new TlsSettings();

    private final WebsiteSettings websiteSettings = new WebsiteSettings();

    private final CorsSettings corsSettings = new CorsSettings();

    private final IcebergCatalogSettings icebergCatalogSettings = new IcebergCatalogSettings();

    /**
     * Set the host that local-s3 service listens on.
     * The default value is {@code 127.0.0.1}, and local only,
     * and {@code 0.0.0.0} makes the service accessible through all network interfaces.
     *
     * @param bindHost host or IP address to bind.
     * @return builder.
     */
    public LocalS3Builder bindHost(@NonNull String bindHost) {
        if (bindHost.isBlank()) {
            throw new IllegalArgumentException("bindHost must not be blank.");
        }
        this.bindHost = bindHost;
        return this;
    }

    public LocalS3Builder acceptFromAnyHost() {
        this.bindHost = "0.0.0.0";
        return this;
    }

    /**
     * Set the port that local-s3 service listen to. Default port is 29090.
     * Set the value to {@code -1} or {@code 0} to bind a random free port, which
     * {@linkplain LocalS3#getPort()} returns once the service is started.
     *
     * @param port customized port.
     * @return builder.
     */
    public LocalS3Builder port(int port) {
        if (port > 65535) {
            throw new IllegalArgumentException("port must not be greater than 65535.");
        }
        // Binding port 0 lets the OS pick a free port, with no window for another process to take it.
        this.port = Math.max(port, 0);
        return this;
    }

    /**
     * Set the LocalS3 data directory. The default value is {@code null},
     * while data is stored in Java Heap.
     *
     * @param dataPath data path.
     * @return builder.
     */
    public LocalS3Builder dataPath(@NonNull String dataPath) {
        return storage(storage -> storage.dataPath(dataPath));
    }

    /**
     * Set LocalS3 service running mode. Default value is {@code IN_MEMORY}.
     *
     * @param mode LocalS3 service running mode.
     * @return builder.
     */
    public LocalS3Builder mode(@NonNull LocalS3Mode mode) {
        return storage(storage -> storage.mode(mode));
    }

    /**
     * The suffix of a bucket name given to {@linkplain #buckets(String...)} that creates the bucket with versioning
     * enabled, e.g. {@code audit:versioned}.
     */
    public static final String VERSIONED_SUFFIX = ":versioned";

    /**
     * Set default buckets. A name with the suffix {@value #VERSIONED_SUFFIX}, e.g. {@code audit:versioned}, is a
     * {@linkplain #versionedBuckets(String...) versioned bucket}, so that {@code AWS_BUCKETS=plain,audit:versioned}
     * creates both.
     *
     * @param buckets LocalS3 buckets
     * @return builder.
     * @throws IllegalArgumentException if a name breaks the bucket naming rules.
     */
    public LocalS3Builder buckets(String... buckets) {
        if (buckets != null) {
            for (String bucket : buckets) {
                // Tolerate lists like "a, b," as split from the AWS_BUCKETS environment variable.
                if (bucket != null && !bucket.isBlank()) {
                    String name = bucket.trim();
                    if (name.endsWith(VERSIONED_SUFFIX)) {
                        versionedBuckets(name.substring(0, name.length() - VERSIONED_SUFFIX.length()));
                    } else {
                        this.defaultBuckets.add(validBucketName(name));
                    }
                }
            }
        }
        return this;
    }

    /**
     * Set default buckets that are created with versioning enabled, like a production bucket that has versioning
     * enabled, without calling {@code PutBucketVersioning} in every test. An existing bucket, e.g. one loaded from the
     * data path, gets versioning enabled if its versioning was never configured; a suspended one stays suspended.
     *
     * @param buckets LocalS3 buckets with versioning enabled.
     * @return builder.
     * @throws IllegalArgumentException if a name breaks the bucket naming rules.
     */
    public LocalS3Builder versionedBuckets(String... buckets) {
        if (buckets != null) {
            for (String bucket : buckets) {
                if (bucket != null && !bucket.isBlank()) {
                    this.versionedBuckets.add(validBucketName(bucket.trim()));
                }
            }
        }
        return this;
    }

    /**
     * Reject a default bucket name while the service is configured, rather than when it starts: a command line or an
     * environment with an invalid name is then reported like any other invalid setting.
     */
    private static String validBucketName(String name) {
        try {
            return BucketAssertions.assertBucketNameFollowsNamingRules(name);
        } catch (InvalidBucketNameException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * Enable AWS Signature Version 4 authentication with a static access key pair.
     *
     * @param accessKeyId     access key ID accepted by the server.
     * @param secretAccessKey secret access key used to verify request signatures.
     * @return builder.
     */
    public LocalS3Builder credentials(@NonNull String accessKeyId, @NonNull String secretAccessKey) {
        if (accessKeyId.isBlank()) {
            throw new IllegalArgumentException("accessKeyId must not be blank.");
        }
        if (secretAccessKey.isBlank()) {
            throw new IllegalArgumentException("secretAccessKey must not be blank.");
        }
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        return this;
    }

    /**
     * Add a {@linkplain LocalS3Seeder seeder} that puts the initial buckets and objects of the service into it: the
     * fixtures that a test or a local run starts with. The seeders are applied in the order they were added, when the
     * service starts, after the {@linkplain #buckets(String...) default buckets} are created and before the server
     * accepts requests, and again after {@linkplain LocalS3#reset()}.
     *
     * @param seeder puts the initial buckets and objects into the service.
     * @return builder.
     */
    public LocalS3Builder seeder(@NonNull LocalS3Seeder seeder) {
        this.seeders.add(Objects.requireNonNull(seeder));
        return this;
    }

    /**
     * Subscribe a listener to the {@linkplain com.robothy.s3.core.event.S3Change changes} that the services commit:
     * buckets created and deleted, objects created and deleted, object tagging and ACLs changed, and multipart uploads
     * aborted. The changes are delivered however the services are called, by an HTTP request or directly through
     * {@linkplain LocalS3#getS3Manager()}. Several listeners may be subscribed; each receives every change.
     *
     * <p>{@linkplain #events(Consumer)} configures the listeners and the executor that delivers the changes to them
     * together.
     *
     * @param changeListener receives the committed changes.
     * @return builder.
     */
    public LocalS3Builder changeListener(@NonNull S3ChangeListener changeListener) {
        return events(events -> events.listener(changeListener));
    }

    /**
     * Configure the builder from the environment variables that the Docker image is configured with, read
     * from the environment or, if a variable isn't set there, from the system property of the same name.
     *
     * <p>Only the variables that are set are applied, so the caller keeps its own defaults for everything
     * else: a container applies its defaults, e.g. binding every interface, before calling this, while an
     * embedded service or a test keeps the defaults of the builder. The variables are
     * {@linkplain LocalS3Environment#LOCAL_S3_PORT}, {@linkplain LocalS3Environment#LOCAL_S3_HOST},
     * {@linkplain LocalS3Environment#LOCAL_S3_MODE},
     * {@linkplain LocalS3Environment#LOCAL_S3_DATA_PATH}, {@linkplain LocalS3Environment#LOCAL_S3_IN_MEMORY_MAX_BYTES},
     * {@linkplain LocalS3Environment#LOCAL_S3_LIFECYCLE_INTERVAL},
     * {@linkplain LocalS3Environment#LOCAL_S3_VIRTUAL_THREADS},
     * {@linkplain LocalS3Environment#LOCAL_S3_COMPOSITE_MULTIPART_ETAGS},
     * {@linkplain LocalS3Environment#LOCAL_S3_VIRTUAL_HOST_DOMAINS}, {@linkplain LocalS3Environment#LOCAL_S3_TLS_CERT},
     * {@linkplain LocalS3Environment#LOCAL_S3_TLS_KEY}, {@linkplain LocalS3Environment#LOCAL_S3_TLS_REQUIRED},
     * {@linkplain LocalS3Environment#LOCAL_S3_ICEBERG_CATALOG},
     * {@linkplain LocalS3Environment#LOCAL_S3_ICEBERG_WAREHOUSE}, {@linkplain LocalS3Environment#AWS_BUCKETS},
     * {@linkplain LocalS3Environment#LOCAL_S3_ACCESS_KEY_ID} and {@linkplain LocalS3Environment#LOCAL_S3_SECRET_ACCESS_KEY}.
     * {@linkplain LocalS3Environment#AWS_ACCESS_KEY_ID} and {@linkplain LocalS3Environment#AWS_SECRET_ACCESS_KEY}, which
     * hold the client credentials of a developer, are read only where
     * {@linkplain LocalS3Environment#LOCAL_S3_CREDENTIALS_FROM_AWS_ENV} is {@code true}.
     *
     * @return builder.
     * @throws IllegalArgumentException if a variable has an invalid value.
     */
    public LocalS3Builder fromEnvironment() {
        return fromEnvironment(name -> Optional.ofNullable(System.getenv(name))
                .orElseGet(() -> System.getProperty(name)));
    }

    /**
     * Configure the builder from the variables that {@code variables} resolves by name, e.g. the entries of
     * a configuration file or of a map in a test. A variable that resolves to {@code null} or to a blank
     * value is not applied.
     *
     * @param variables resolves the value of a variable by name.
     * @return builder.
     * @throws IllegalArgumentException if a variable has an invalid value.
     */
    public LocalS3Builder fromEnvironment(@NonNull UnaryOperator<String> variables) {
        LocalS3Environment.applyTo(this, variables);
        return this;
    }

    /**
     * Configure the storage of the service: the mode it runs in, the data directory it keeps, when its changes reach
     * the disk and how much content it holds. The settings of this domain are grouped, rather than spread over the
     * builder, so that the knobs a service is rarely tuned with stay out of the way of the ones it is usually built
     * with:
     *
     * <pre>{@code
     *  LocalS3.builder()
     *      .storage(storage -> storage.mode(LocalS3Mode.PERSISTENCE)
     *          .dataPath("/tmp/local-s3")
     *          .persistencePolicy(PersistencePolicy.FAST))
     *      .build();
     * }</pre>
     *
     * <p>The two a service is usually built with, {@linkplain #mode(LocalS3Mode)} and
     * {@linkplain #dataPath(String)}, are methods of the builder as well.
     *
     * @param storage configures the storage of the service.
     * @return builder.
     * @throws IllegalArgumentException if a setting has an invalid value.
     */
    public LocalS3Builder storage(@NonNull Consumer<StorageSettings> storage) {
        storage.accept(storageSettings);
        return this;
    }

    /**
     * Configure the HTTP server: the threads that serve the requests, the limits that a request is held to, how the
     * server relates to the JVM it runs in, and the recorder of the requests it answered. The settings of this domain
     * are grouped, rather than spread over the builder, so that the knobs a service is rarely tuned with stay out of
     * the way of the ones it is usually built with:
     *
     * <pre>{@code
     *  LocalS3.builder()
     *      .netty(netty -> netty.virtualThreads(false)
     *          .s3ExecutorThreadNum(16)
     *          .maxRequestBodySize(64 * 1024 * 1024)
     *          .idleConnectionTimeoutSeconds(30))
     *      .build();
     * }</pre>
     *
     * @param netty configures the HTTP server of the service.
     * @return builder.
     * @throws IllegalArgumentException if a setting has an invalid value.
     */
    public LocalS3Builder netty(@NonNull Consumer<NettySettings> netty) {
        netty.accept(nettySettings);
        return this;
    }

    /**
     * Configure how the S3 API itself answers: the domains that address a bucket by the host of a request, and the
     * entity tags that the objects of completed multipart uploads get. The settings of this domain are grouped, so
     * that the knobs a service is rarely tuned with stay out of the way of the ones it is usually built with:
     *
     * <pre>{@code
     *  LocalS3.builder()
     *      .s3Api(s3 -> s3.virtualHostDomains("s3.local").compositeMultipartEtags(false))
     *      .build();
     * }</pre>
     *
     * @param s3Api configures the S3 API of the service.
     * @return builder.
     */
    public LocalS3Builder s3Api(@NonNull Consumer<S3ApiSettings> s3Api) {
        s3Api.accept(s3ApiSettings);
        return this;
    }

    /**
     * Configure the {@linkplain com.robothy.s3.core.event.S3Change changes} that the service publishes: the listeners
     * that receive them, and the executor that delivers them. The settings of this domain are grouped:
     *
     * <pre>{@code
     *  LocalS3.builder()
     *      .events(events -> events.listener(change -> log.info("{}", change))
     *          .executor(Executors.newSingleThreadExecutor()))
     *      .build();
     * }</pre>
     *
     * <p>{@linkplain #changeListener(S3ChangeListener)}, the one-liner that subscribes a single listener, is a method
     * of the builder as well.
     *
     * @param events configures the change listeners of the service.
     * @return builder.
     */
    public LocalS3Builder events(@NonNull Consumer<EventSettings> events) {
        events.accept(eventSettings);
        return this;
    }

    /**
     * Configure when the service applies the lifecycle configurations of its buckets by itself, which it never does by
     * default, so that nothing expires while a test runs; a test applies them with
     * {@linkplain LocalS3#applyLifecycle(java.time.Instant)} instead. A service that runs for a long time, e.g. one
     * embedded in an IDE or holding the artifacts of an AI agent, applies them on a schedule, so that what the rules
     * expire doesn't pile up:
     *
     * <pre>{@code
     *  LocalS3.builder()
     *      .lifecycle(lifecycle -> lifecycle.applyEvery(Duration.ofHours(1)))
     *      .build();
     * }</pre>
     *
     * @param lifecycle configures when the lifecycle configurations are applied.
     * @return builder.
     * @throws IllegalArgumentException if the interval is negative.
     */
    public LocalS3Builder lifecycle(@NonNull Consumer<LifecycleSettings> lifecycle) {
        lifecycle.accept(lifecycleSettings);
        return this;
    }

    /**
     * Serve HTTPS with a certificate and its private key in PEM format. Clients that use HTTPS by default, e.g.
     * DuckDB, Hadoop S3A or the {@code object_store} crate, then connect without turning TLS off.
     *
     * <p>For local development, <a href="https://github.com/FiloSottile/mkcert">mkcert</a> creates a certificate that
     * the machine trusts: {@code mkcert -install} once, then {@code mkcert localhost 127.0.0.1} creates
     * {@code localhost+1.pem} and {@code localhost+1-key.pem}. A JVM client trusts it only once the CA of mkcert,
     * {@code $(mkcert -CAROOT)/rootCA.pem}, is in its trust store.
     *
     * <p>The port answers <b>both HTTP and HTTPS</b>: every connection is told apart by its first bytes, so a client
     * that speaks TLS and one that doesn't share the endpoint, and a test suite doesn't need two services to cover
     * both. {@code tls(tls -> tls.required(true))} serves HTTPS alone instead.
     *
     * <p>The files are read, and the certificate and key validated, when this method is called.
     *
     * @param certPem the certificate, optionally followed by its intermediate certificates: the path of a PEM file, or
     *     the PEM content itself.
     * @param keyPem the unencrypted PKCS#8 private key of the certificate, {@code -----BEGIN PRIVATE KEY-----}: the path
     *     of a PEM file, or the PEM content itself.
     * @return builder.
     * @throws IllegalArgumentException if a file can't be read, or the certificate and key are invalid.
     */
    public LocalS3Builder tls(@NonNull String certPem, @NonNull String keyPem) {
        tlsSettings.certificate(certPem, keyPem);
        return this;
    }

    /**
     * Serve HTTPS instead of plain HTTP with the certificate and private key of PEM files; see
     * {@linkplain #tls(String, String)}.
     *
     * @param certPemFile the PEM file of the certificate chain.
     * @param keyPemFile the PEM file of the unencrypted PKCS#8 private key.
     * @return builder.
     * @throws IllegalArgumentException if a file can't be read, or the certificate and key are invalid.
     */
    public LocalS3Builder tls(@NonNull Path certPemFile, @NonNull Path keyPemFile) {
        return tls(certPemFile.toString(), keyPemFile.toString());
    }

    /**
     * Serve HTTPS instead of plain HTTP with a certificate and private key that the caller holds, e.g. one generated
     * for {@code localhost} where no certificate of the machine is at hand:
     *
     * <pre>{@code LocalS3.builder().tls(LocalS3Tls.selfSigned()).build()}</pre>
     *
     * <p>A client has to be given a {@linkplain LocalS3Tls#selfSigned() self-signed} certificate, which nothing trusts
     * by default; the service logs it in PEM format when it starts, and
     * {@linkplain LocalS3Tls#newClientSslContext()} trusts it in the JVM that embeds the service.
     *
     * @param tls the certificate and its private key; see {@linkplain #tls(String, String)}.
     * @return builder.
     */
    public LocalS3Builder tls(@NonNull LocalS3Tls tls) {
        tlsSettings.certificate(tls);
        return this;
    }

    /**
     * Configure HTTPS: the certificate the service serves, and whether it serves HTTPS alone. The settings of this
     * domain are grouped, so that a certificate and the way it is served are configured in one place:
     *
     * <pre>{@code
     *  LocalS3.builder().tls(tls -> tls.selfSigned().required(true)).build();
     *  LocalS3.builder().tls(tls -> tls.certificate(certPem, keyPem)).build();
     * }</pre>
     *
     * @param tls configures the HTTPS of the service.
     * @return builder.
     * @throws IllegalArgumentException if a certificate file can't be read, or the certificate and key are invalid.
     */
    public LocalS3Builder tls(@NonNull Consumer<TlsSettings> tls) {
        tls.accept(tlsSettings);
        return this;
    }

    /**
     * Serve the buckets as static websites to the requests that carry no credentials, which is on by default: a
     * browser that opens {@code http://localhost:{port}/{bucket}/} gets the index document of the bucket, and a key
     * that isn't there gets its error document, while the signed requests of an S3 client keep their S3 semantics.
     *
     * <p>Only a bucket that was made public answers such a request, see
     * {@linkplain com.robothy.s3.core.util.BucketPublicAccess};
     * {@code website(website -> website.allBuckets(true))} serves every bucket instead.
     *
     * @param enabled {@code true} to serve static websites; {@code false} to leave every request to the S3 API.
     * @return builder.
     */
    public LocalS3Builder website(boolean enabled) {
        websiteSettings.enabled(enabled);
        return this;
    }

    /**
     * Serve static websites with settings of your own; see {@linkplain #website(boolean)}. The settings of this domain
     * are grouped:
     *
     * <pre>{@code
     *  LocalS3.builder()
     *      .website(website -> website.allBuckets(true).indexDocument("home.html").errorDocument("404.html"))
     *      .build();
     * }</pre>
     *
     * @param website configures the static website hosting of the service.
     * @return builder.
     * @throws IllegalArgumentException if the index document is blank.
     */
    public LocalS3Builder website(@NonNull Consumer<WebsiteSettings> website) {
        website.accept(websiteSettings);
        return this;
    }

    /**
     * Allow the cross-origin requests of browsers by a default CORS rule, which applies where no CORS configuration of
     * a bucket does: to the buckets that weren't configured with {@code PutBucketCors}, and to the requests that
     * address no bucket, e.g. {@code ListBuckets} and the Iceberg REST catalog. A bucket that has a configuration of
     * its own keeps it, like in Amazon S3. It is off by default:
     *
     * <pre>{@code
     *  LocalS3.builder()
     *      .defaultCors(cors -> cors.allowedOrigins("http://localhost:5173"))
     *      .build();
     * }</pre>
     *
     * <p>Unless told otherwise, the rule allows every method that a CORS rule of Amazon S3 may allow, every request
     * header, and exposes the response headers that the clients of a browser read, e.g. {@code ETag}; see
     * {@linkplain LocalS3Cors}. An allowed origin, and {@code *} above all, lets its pages reach the data of the
     * service from a browser, which is meant for local development.
     *
     * @param cors configures the default CORS rule of the service.
     * @return builder.
     * @throws IllegalArgumentException if a method isn't one that Amazon S3 allows, or a value is blank.
     */
    public LocalS3Builder defaultCors(@NonNull Consumer<CorsSettings> cors) {
        cors.accept(corsSettings);
        return this;
    }

    /**
     * Allow the cross-origin requests of browsers by a default CORS rule; see {@linkplain #defaultCors(Consumer)}.
     *
     * @param cors the default rule; {@code null} for none, which is the default.
     * @return builder.
     */
    public LocalS3Builder defaultCors(LocalS3Cors cors) {
        corsSettings.settings(cors);
        return this;
    }

    /**
     * Serve an <a href="https://iceberg.apache.org/spec/#rest-catalog">Iceberg REST catalog</a> beside the S3 API, on
     * the same port, under {@code /iceberg/v1}, so that a test of Apache Iceberg needs no catalog of its own:
     *
     * <pre>{@code
     *  LocalS3 s3 = LocalS3.builder().port(29090).icebergCatalog(true).build();
     *  s3.start();
     *
     *  RESTCatalog catalog = new RESTCatalog();
     *  catalog.initialize("local", Map.of("uri", "http://localhost:29090/iceberg"));
     *  catalog.createNamespace(Namespace.of("db"));
     * }</pre>
     *
     * <p>The tables are stored in LocalS3 itself, under the warehouse {@value LocalS3IcebergCatalog#DEFAULT_WAREHOUSE},
     * whose bucket is created when the service starts; {@linkplain #icebergCatalog(Consumer)} configures
     * that. An {@code IN_MEMORY} service therefore holds its tables in memory and a {@code PERSISTENCE} service keeps
     * them in its data directory, like everything else it stores.
     *
     * <p>The catalog is off by default: a service that doesn't ask for one carries neither its routes nor its state.
     *
     * @param enabled {@code true} to serve the catalog with the default settings; {@code false}, the default, to serve
     *     none.
     * @return builder.
     */
    public LocalS3Builder icebergCatalog(boolean enabled) {
        icebergCatalogSettings.settings(enabled ? LocalS3IcebergCatalog.enabled() : null);
        return this;
    }

    /**
     * Serve an Iceberg REST catalog with settings of your own, which also turns the catalog on; see
     * {@linkplain #icebergCatalog(boolean)}. The settings of this domain are grouped:
     *
     * <pre>{@code
     *  LocalS3.builder()
     *      .icebergCatalog(iceberg -> iceberg.warehouse("s3://lakehouse/").credentialVending(false))
     *      .build();
     * }</pre>
     *
     * <p>A service that has no catalog yet gets one with the {@linkplain LocalS3IcebergCatalog#enabled() defaults}
     * before the settings are applied, since configuring a catalog is asking for one;
     * {@code icebergCatalog(iceberg -> iceberg.enabled(false))} serves none after all.
     *
     * @param iceberg configures the Iceberg REST catalog of the service.
     * @return builder.
     * @throws IllegalArgumentException if the warehouse isn't an {@code s3://} URI of a bucket.
     */
    public LocalS3Builder icebergCatalog(@NonNull Consumer<IcebergCatalogSettings> iceberg) {
        icebergCatalogSettings.enabled(true);
        iceberg.accept(icebergCatalogSettings);
        return this;
    }

    /**
     * Build the configuration of a {@linkplain LocalS3} service from the values set so far. Changing the builder
     * afterwards doesn't change the configuration.
     *
     * @return the configuration.
     */
    public LocalS3Config buildConfig() {
        StorageSettings storage = storageSettings;
        NettySettings netty = nettySettings;
        S3ApiSettings s3Api = s3ApiSettings;
        return new LocalS3Config(bindHost, port, storage.dataPath, storage.mode, storage.persistencePolicy,
                defaultBuckets, versionedBuckets, seeders, eventSettings.listeners, eventSettings.executor,
                storage.initialDataCacheEnabled, storage.maxInMemoryBytes, netty.daemonThreads,
                netty.registerShutdownHook, netty.parentEventGroupThreadNum, netty.childEventGroupThreadNum,
                netty.s3ExecutorThreadNum, netty.virtualThreads, accessKeyId, secretAccessKey,
                netty.maxRequestBodySize, netty.requestBodyFileThreshold, netty.maxRequestHeaderSize,
                netty.idleConnectionTimeoutSeconds, s3Api.compositeMultipartEtags, s3Api.acceptChunkedUploads,
                s3Api.virtualHostDomains, netty.requestRecorder, tlsSettings.tls, tlsSettings.required,
                icebergCatalogSettings.icebergCatalog, websiteSettings.website, corsSettings.cors,
                lifecycleSettings.interval);
    }

    /**
     * Build a {@linkplain LocalS3} instance.
     *
     * @return created {@linkplain LocalS3} instance.
     */
    public LocalS3 build() {
        LocalS3Config config = buildConfig();
        log.debug("Build LocalS3 on {}:{} in {} mode, data path: {}, authentication: {}, TLS: {}.", config.bindHost(),
                config.port(), config.mode(), config.dataPath(), config.authenticationEnabled() ? "enabled" : "disabled",
                config.tlsEnabled() ? "enabled" : "disabled");
        return new LocalS3(config);
    }

}
