package com.robothy.s3.datatypes.converter;

import java.time.Instant;
import java.util.Date;
import tools.jackson.databind.util.StdConverter;

/**
 * Convert to Amazon instant.
 */
public class AmazonInstantConverter extends StdConverter<Instant, String> {

  @Override
  public String convert(Instant value) {
    return value.toString();
  }

}
