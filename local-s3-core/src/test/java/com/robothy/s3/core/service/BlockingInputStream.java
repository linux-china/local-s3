package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A stream whose reads block until it is released, so that a test can act while a service reads it.
 */
class BlockingInputStream extends FilterInputStream {

  private final CountDownLatch reading = new CountDownLatch(1);

  private final CountDownLatch released = new CountDownLatch(1);

  BlockingInputStream(byte[] data) {
    super(new ByteArrayInputStream(data));
  }

  /**
   * Wait until a service reads the stream.
   */
  void awaitReading() throws InterruptedException {
    assertTrue(reading.await(5, TimeUnit.SECONDS), "The stream isn't read.");
  }

  void release() {
    released.countDown();
  }

  @Override
  public int read() throws IOException {
    block();
    return super.read();
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    block();
    return super.read(b, off, len);
  }

  private void block() throws IOException {
    reading.countDown();
    try {
      if (!released.await(10, TimeUnit.SECONDS)) {
        throw new IOException("The stream isn't released.");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException();
    }
  }

}
