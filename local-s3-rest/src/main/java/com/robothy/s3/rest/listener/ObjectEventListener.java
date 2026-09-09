package com.robothy.s3.rest.listener;

@FunctionalInterface
public interface ObjectEventListener {
    void onObjectEvent(ObjectEvent event);

}
