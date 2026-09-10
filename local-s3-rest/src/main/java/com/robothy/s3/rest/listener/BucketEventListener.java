package com.robothy.s3.rest.listener;

import org.jspecify.annotations.NonNull;

@FunctionalInterface
public interface BucketEventListener {
    void onBucketEvent(@NonNull BucketEvent event);
}
