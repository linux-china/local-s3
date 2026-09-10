package com.robothy.s3.rest.listener;

import org.jspecify.annotations.NonNull;

@FunctionalInterface
public interface ObjectEventListener {
    void onObjectEvent(@NonNull ObjectEvent event);

}
