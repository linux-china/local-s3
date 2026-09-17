package com.robothy.s3.core.model;

/**
 * The object lock configuration of a bucket that has Object Lock enabled. A bucket without Object Lock has none.
 *
 * @param defaultRetention the retention that new object versions get if they are stored without one; {@code null} for
 *     none.
 */
public record BucketObjectLockConfiguration(DefaultRetention defaultRetention) {

}
