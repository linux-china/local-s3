package com.robothy.s3.rest.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the services of one LocalS3 service.
 *
 * <p>The registry belongs to the instance, so that services started in the same JVM keep their own
 * configuration, e.g. the {@linkplain BucketNameValidator} or the {@linkplain MultipartUploadPolicy} that
 * their builder set. It is a {@linkplain ConcurrentHashMap} because the registry is filled while the service
 * starts and read afterwards by the threads that handle the requests.
 */
public class DefaultServiceFactory implements ServiceFactory {

  private static final Logger log = LoggerFactory.getLogger(DefaultServiceFactory.class);

  private final Map<Class<?>, Supplier<?>> factoryMap = new ConcurrentHashMap<>();

  @Override
  public <T> void register(Class<T> clazz, Supplier<? extends T> factory) {
    factoryMap.put(clazz, factory);
    log.debug("Registered service {}.", clazz.getName());
  }

  @Override
  public <T> T getInstance(Class<T> clazz) {
    Supplier<?> factory = factoryMap.get(clazz);
    if (factory == null) {
      throw new IllegalArgumentException("Not cannot find service factory for " + clazz.getName() + ".");
    }

    //noinspection unchecked
    return (T) factory.get();
  }

  @Override
  public boolean containsInstance(Class<?> clazz) {
    return factoryMap.containsKey(clazz);
  }
}
