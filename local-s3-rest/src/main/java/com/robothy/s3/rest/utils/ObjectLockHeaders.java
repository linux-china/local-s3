package com.robothy.s3.rest.utils;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.ObjectLockMode;
import com.robothy.s3.core.model.internal.ObjectLock;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Reads the Object Lock settings that a request stores an object with from the {@code x-amz-object-lock-*} headers, and
 * writes the ones of an object to the headers of a response that serves it.
 */
public final class ObjectLockHeaders {

  private static final String LEGAL_HOLD_ON = "ON";

  private static final String LEGAL_HOLD_OFF = "OFF";

  /**
   * Formats a retain until date like Amazon S3 does, e.g. {@code 2030-01-01T00:00:00.000Z}.
   */
  private static final DateTimeFormatter ISO_8601 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(java.time.ZoneOffset.UTC);

  private ObjectLockHeaders() {
  }

  /**
   * Read the Object Lock settings of a {@code PutObject}, {@code CopyObject} or {@code CreateMultipartUpload} request.
   *
   * @param request the request.
   * @return the settings; {@code null} if the request sends none.
   * @throws LocalS3InvalidArgumentException if a header carries an invalid value.
   */
  public static ObjectLock fromRequest(HttpRequest request) {
    String modeHeader = request.header(AmzHeaderNames.X_AMZ_OBJECT_LOCK_MODE).orElse(null);
    String dateHeader = request.header(AmzHeaderNames.X_AMZ_OBJECT_LOCK_RETAIN_UNTIL_DATE).orElse(null);
    String legalHoldHeader = request.header(AmzHeaderNames.X_AMZ_OBJECT_LOCK_LEGAL_HOLD).orElse(null);
    if (modeHeader == null && dateHeader == null && legalHoldHeader == null) {
      return null;
    }
    ObjectLockMode mode = null;
    if (modeHeader != null) {
      mode = parseMode(AmzHeaderNames.X_AMZ_OBJECT_LOCK_MODE, modeHeader);
    }
    Long retainUntilDate = dateHeader == null ? null
        : parseDate(AmzHeaderNames.X_AMZ_OBJECT_LOCK_RETAIN_UNTIL_DATE, dateHeader);
    Boolean legalHold = legalHoldHeader == null ? null
        : parseLegalHold(AmzHeaderNames.X_AMZ_OBJECT_LOCK_LEGAL_HOLD, legalHoldHeader);
    return new ObjectLock(mode, retainUntilDate, legalHold);
  }

  /**
   * Add the Object Lock settings of an object to a response that serves it.
   *
   * @param response the response.
   * @param lock the settings of the object; {@code null} if it has none.
   */
  public static void addHeaders(HttpResponse response, ObjectLock lock) {
    if (lock == null) {
      return;
    }
    if (lock.hasRetention()) {
      response.putHeader(AmzHeaderNames.X_AMZ_OBJECT_LOCK_MODE, lock.mode().name())
          .putHeader(AmzHeaderNames.X_AMZ_OBJECT_LOCK_RETAIN_UNTIL_DATE, formatDate(lock.retainUntilDate()));
    }
    if (lock.legalHold() != null) {
      response.putHeader(AmzHeaderNames.X_AMZ_OBJECT_LOCK_LEGAL_HOLD, formatLegalHold(lock.legalHold()));
    }
  }

  /**
   * Parse a retention mode.
   *
   * @param name the name of the header or element that carries it, which an error reports.
   * @param value the mode.
   * @return the mode.
   * @throws LocalS3InvalidArgumentException if the value isn't a mode.
   */
  public static ObjectLockMode parseMode(String name, String value) {
    ObjectLockMode mode = ObjectLockMode.parse(value.trim());
    if (mode == null) {
      throw new LocalS3InvalidArgumentException(name, value, "Unknown wormMode directive.");
    }
    return mode;
  }

  /**
   * Parse an ISO 8601 date and time, e.g. {@code 2030-01-01T00:00:00.000Z}.
   *
   * @param name the name of the header or element that carries it, which an error reports.
   * @param value the date.
   * @return the epoch milliseconds.
   * @throws LocalS3InvalidArgumentException if the value isn't an ISO 8601 date and time.
   */
  public static long parseDate(String name, String value) {
    try {
      return OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli();
    } catch (DateTimeParseException e) {
      try {
        return Instant.parse(value.trim()).toEpochMilli();
      } catch (DateTimeParseException ignored) {
        throw new LocalS3InvalidArgumentException(name, value, "The retain until date must be an ISO 8601 date.");
      }
    }
  }

  /**
   * Format a date like Amazon S3 does.
   *
   * @param epochMilli the epoch milliseconds.
   * @return the ISO 8601 date and time.
   */
  public static String formatDate(long epochMilli) {
    return ISO_8601.format(Instant.ofEpochMilli(epochMilli));
  }

  /**
   * Parse the status of a legal hold.
   *
   * @param name the name of the header or element that carries it, which an error reports.
   * @param value {@code ON} or {@code OFF}.
   * @return {@code true} for {@code ON}.
   * @throws LocalS3InvalidArgumentException if the value is neither.
   */
  public static boolean parseLegalHold(String name, String value) {
    return switch (value.trim()) {
      case LEGAL_HOLD_ON -> true;
      case LEGAL_HOLD_OFF -> false;
      default -> throw new LocalS3InvalidArgumentException(name, value, "Legal Hold must be either of 'ON' or 'OFF'");
    };
  }

  /**
   * Format the status of a legal hold.
   *
   * @param on whether the legal hold is on.
   * @return {@code ON} or {@code OFF}.
   */
  public static String formatLegalHold(boolean on) {
    return on ? LEGAL_HOLD_ON : LEGAL_HOLD_OFF;
  }

}
