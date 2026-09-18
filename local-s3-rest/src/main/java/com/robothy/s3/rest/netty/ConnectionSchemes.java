package com.robothy.s3.rest.netty;

import com.robothy.netty.http.HttpRequest;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * The scheme that a request reached the service by, {@code http} or {@code https}, which a port that answers both,
 * see {@linkplain LocalS3ServerInitializer}, can't read off the configuration: the {@code Location} of a browser form
 * upload names the URL of the object it stored, and a client that posted the form over plain HTTP to a service that
 * also serves HTTPS is given an {@code http} URL rather than one it can't use.
 *
 * <p>{@linkplain com.robothy.netty.http.HttpRequest} carries nothing but what the client sent, so the scheme of a
 * request is kept here instead of in the request, by the identity of the request and only while it exists: the keys
 * are weak, and compared by identity, since {@code HttpRequest} doesn't override {@code equals}. An entry is dropped
 * with the request it belongs to, and no request of a client can read another's.
 *
 * <p>Only the connections of a port that answers both schemes are recorded, see {@linkplain #mixed}: where the port
 * serves one of them, every request arrived by that one, and the caller reads it off the configuration instead. So a
 * service that serves plain HTTP, which is the usual one, keeps nothing here at all.
 */
public final class ConnectionSchemes {

  public static final String HTTP = "http";

  public static final String HTTPS = "https";

  /**
   * Set on the connections of a port that answers both schemes, whose requests are the ones worth recording.
   */
  private static final AttributeKey<Boolean> MIXED = AttributeKey.valueOf(ConnectionSchemes.class, "mixed");

  /**
   * Set on the connections that turned out to be TLS, by the handler that decrypts them.
   */
  private static final AttributeKey<Boolean> ENCRYPTED =
      AttributeKey.valueOf(ConnectionSchemes.class, "encrypted");

  private static final Map<HttpRequest, String> SCHEMES = Collections.synchronizedMap(new WeakHashMap<>());

  private ConnectionSchemes() {
  }

  /**
   * Record that a connection may turn out to be either scheme, i.e. that it arrived on a port that answers both.
   *
   * @param channel the connection.
   */
  static void mixed(Channel channel) {
    channel.attr(MIXED).set(Boolean.TRUE);
  }

  /**
   * Record that a connection is encrypted, i.e. that its requests arrive over HTTPS.
   *
   * @param channel the connection.
   */
  static void https(Channel channel) {
    channel.attr(ENCRYPTED).set(Boolean.TRUE);
  }

  /**
   * Record the scheme of a decoded request, from the connection it arrived on. A connection of a port that serves one
   * scheme is left out: nothing about it is worth a lookup, since every request of the service arrived the same way.
   *
   * @param channel the connection that the request arrived on.
   * @param request the decoded request.
   */
  static void record(Channel channel, HttpRequest request) {
    if (Boolean.TRUE.equals(channel.attr(MIXED).get())) {
      SCHEMES.put(request, Boolean.TRUE.equals(channel.attr(ENCRYPTED).get()) ? HTTPS : HTTP);
    }
  }

  /**
   * The scheme that a request arrived by.
   *
   * @param request the request.
   * @return {@code "https"} if the request arrived over TLS, {@code "http"} if it didn't; empty for a request that
   *     arrived on a port that serves a single scheme, which the caller knows from the configuration, or that no
   *     connection decoded at all, e.g. one built by a test.
   */
  public static Optional<String> of(HttpRequest request) {
    return Optional.ofNullable(SCHEMES.get(request));
  }

}
