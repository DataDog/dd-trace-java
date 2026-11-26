package com.datadog.appsec.event.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AddressTest {

  @Test
  void toStringIncludesKey() {
    assertEquals(
        "Address{key='server.request.uri.raw'}", KnownAddresses.REQUEST_URI_RAW.toString());
  }
}
