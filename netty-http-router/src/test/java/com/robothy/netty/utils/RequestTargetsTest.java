package com.robothy.netty.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class RequestTargetsTest {

  @Test
  void toOriginForm() {
    assertEquals("/a/b?c=d", RequestTargets.toOriginForm("/a/b?c=d"));
    assertEquals("/a/b?c=d", RequestTargets.toOriginForm("http://host:8080/a/b?c=d"));
    assertEquals("/a", RequestTargets.toOriginForm("HTTPS://host/a"));
    assertEquals("/", RequestTargets.toOriginForm("http://host"));
    assertEquals("/?list-type=2", RequestTargets.toOriginForm("http://host?list-type=2"));

    assertNull(RequestTargets.toOriginForm(null));
    assertNull(RequestTargets.toOriginForm(""));
    assertNull(RequestTargets.toOriginForm("*"));
    assertNull(RequestTargets.toOriginForm("host:443"));
    assertNull(RequestTargets.toOriginForm("ftp://host/a"));
    assertNull(RequestTargets.toOriginForm("http:///a"));
  }

  @Test
  void parse() {
    assertEquals(new RequestTargets.RequestTarget("/a/b?c=d", null), RequestTargets.parse("/a/b?c=d"));
    assertEquals(new RequestTargets.RequestTarget("/a/b?c=d", "host:8080"),
        RequestTargets.parse("http://host:8080/a/b?c=d"));
    assertEquals(new RequestTargets.RequestTarget("/", "bucket.localhost"), RequestTargets.parse("http://bucket.localhost"));
    assertEquals(new RequestTargets.RequestTarget("/?list-type=2", "host"),
        RequestTargets.parse("https://host?list-type=2"));
    // The user info isn't part of the Host header.
    assertEquals(new RequestTargets.RequestTarget("/a", "host:9000"), RequestTargets.parse("http://user:p@ss@host:9000/a"));
    assertEquals(new RequestTargets.RequestTarget("/a", "[::1]:9000"), RequestTargets.parse("http://[::1]:9000/a"));

    // An http(s) URI without a host is invalid.
    assertNull(RequestTargets.parse("http:///a"));
    assertNull(RequestTargets.parse("http://user@/a"));
    assertNull(RequestTargets.parse("*"));
  }

  @Test
  void fragmentIsRemoved() {
    assertEquals(new RequestTargets.RequestTarget("/", "host"), RequestTargets.parse("http://host#frag"));
    assertEquals(new RequestTargets.RequestTarget("/?a=1", "host"), RequestTargets.parse("http://host?a=1#frag"));
    assertEquals(new RequestTargets.RequestTarget("/a/b", "host"), RequestTargets.parse("http://host/a/b#c?d"));
    assertEquals(new RequestTargets.RequestTarget("/a?b=c", null), RequestTargets.parse("/a?b=c#d"));
    // A percent-encoded '#' is part of the path.
    assertEquals(new RequestTargets.RequestTarget("/a%23b", null), RequestTargets.parse("/a%23b"));
    // Nothing is left of a target that is only a fragment.
    assertNull(RequestTargets.parse("#frag"));
  }

}
