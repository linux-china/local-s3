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
  }

}
