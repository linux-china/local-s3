package com.robothy.s3.core.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a default service method that is invoked on the service proxy instead of the service, so that the
 * service methods it calls go through the proxy and are locked and persisted as annotated.
 *
 * <p>The method itself doesn't lock the bucket, e.g. to store the content of an upload before it adds the
 * metadata under the bucket write lock. Don't combine it with the lock or persistence annotations.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CallsThroughProxy {
}
