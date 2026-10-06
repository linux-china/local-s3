package com.robothy.s3.rest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The settings of the S3 API of a service: the domains that address a bucket by the host of a request, and the
 * entity tags that the objects of completed multipart uploads get.
 * {@linkplain LocalS3Builder#s3Api(Consumer)} hands out the one that the builder builds its configuration from.
 */
public final class S3ApiSettings {

    final List<String> virtualHostDomains = new ArrayList<>();

    boolean compositeMultipartEtags = true;

    boolean acceptChunkedUploads = true;

    Duration allowedClockSkew = LocalS3Config.DEFAULT_ALLOWED_CLOCK_SKEW;

    S3ApiSettings() {
    }

    /**
     * Add base domains of virtual-hosted-style requests. With the domain {@code s3.local}, a request to the
     * host {@code my-bucket.s3.local} accesses the bucket {@code my-bucket}, while requests to {@code s3.local}
     * itself are path-style. This lets clients use virtual-hosted-style requests with a host name like the
     * service name in docker-compose. {@code localhost}, {@code 127.0.0.1} and {@code 0.0.0.0} are always
     * base domains; hosts of Amazon S3 ({@code amazonaws.com}), of Alibaba Cloud OSS ({@code aliyuncs.com},
     * e.g. {@code my-bucket.oss-cn-hangzhou.aliyuncs.com}) and of Cloudflare R2 ({@code r2.cloudflarestorage.com},
     * e.g. {@code my-bucket.<account-id>.r2.cloudflarestorage.com}) and of Tigris ({@code my-bucket.t3.storage.dev},
     * {@code my-bucket.fly.storage.tigris.dev}) are supported as well.
     *
     * @param domains base domains, e.g. {@code s3} or {@code s3.local}.
     * @return these settings.
     */
    public S3ApiSettings virtualHostDomains(String... domains) {
        if (domains != null) {
            for (String domain : domains) {
                if (domain != null && !domain.isBlank()) {
                    this.virtualHostDomains.add(domain.trim());
                }
            }
        }
        return this;
    }

    /**
     * Set whether the object of a completed multipart upload gets the entity tag that Amazon S3 gives an
     * object uploaded in parts: the MD5 digest of the concatenated MD5 digests of its parts, followed by
     * {@code -} and the number of parts, e.g. {@code 3858f62230ac3c915f300c664312c11f-9}. The
     * {@code -<parts>} suffix is what a client reads the part layout of an object off, so code that tells
     * an object uploaded in parts from one uploaded at once, e.g. to decide whether the entity tag may be
     * compared with the MD5 of a local file, takes the same branch as against Amazon S3.
     *
     * <p>The default value is {@code true}. Pass {@code false} to give the object the MD5 digest of its
     * whole content instead, which is what LocalS3 gave it before 2.5, e.g. for a test that asserts that
     * entity tag.
     *
     * @param compositeMultipartEtags whether to give the objects of completed uploads the entity tag of
     *     Amazon S3.
     * @return these settings.
     */
    public S3ApiSettings compositeMultipartEtags(boolean compositeMultipartEtags) {
        this.compositeMultipartEtags = compositeMultipartEtags;
        return this;
    }

    /**
     * Set whether {@code PutObject} and {@code UploadPart} store a body that is sent with
     * {@code Transfer-Encoding: chunked} and no {@code Content-Length}, i.e. a stream whose length the client
     * didn't know when it started, e.g. {@code curl -T -}, a streaming {@code fetch} of Node.js or Deno, or a
     * lightweight S3 library that streams. The body is received whole, and stored with the number of bytes
     * received as its length.
     *
     * <p>The default value is {@code true}, so that such a client works against LocalS3. Pass {@code false} to
     * answer {@code 411 MissingContentLength}, like Amazon S3 does, e.g. to test that a client declares the
     * length of what it uploads. An {@code aws-chunked} body, which the AWS SDKs send, declares its length in
     * {@code x-amz-decoded-content-length} and isn't affected.
     *
     * @param acceptChunkedUploads whether to store a body of undeclared length.
     * @return these settings.
     */
    public S3ApiSettings acceptChunkedUploads(boolean acceptChunkedUploads) {
        this.acceptChunkedUploads = acceptChunkedUploads;
        return this;
    }

    /**
     * Set how far the time of a signed request, its {@code x-amz-date} or {@code Date}, may be from the clock of the
     * service before the request is answered with {@code 403 RequestTimeTooSkewed}. Only applies when
     * {@linkplain LocalS3Builder#credentials(String, String) credentials} are configured.
     *
     * <p>The default value is 15 minutes, like Amazon S3. The clock of a Docker Desktop, WSL2 or Colima VM often drifts
     * after the host sleeps; a larger value tolerates that, and {@linkplain Duration#ZERO} turns the time check off, so
     * that only the signature itself is verified and presigned URLs don't expire.
     *
     * @param allowedClockSkew the allowed difference, not negative; zero turns the time check off.
     * @return these settings.
     */
    public S3ApiSettings allowedClockSkew(Duration allowedClockSkew) {
        LocalS3Config.requireAllowedClockSkew(Objects.requireNonNull(allowedClockSkew, "allowedClockSkew"));
        this.allowedClockSkew = allowedClockSkew;
        return this;
    }

}
