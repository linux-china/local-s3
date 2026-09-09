package com.robothy.s3.rest.listener;

@FunctionalInterface
public interface BucketEventListener {
    void onBucketEvent(BucketEvent event);
}
