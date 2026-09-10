package com.robothy.s3.rest.bootstrap;

import java.util.Arrays;

public enum LocalS3Mode {

  /**
   * Persist the data.
   */
  PERSISTENCE,

  /**
   * Store data in memory. Data will be lost after restart the service.
   */
  IN_MEMORY;

  public static boolean isLegalName(String mode) {
    return mode != null && Arrays.stream(values())
            .anyMatch(m -> m.name().equalsIgnoreCase(mode));
  }
}
