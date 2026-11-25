package com.datadog.appsec.event.data;

import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_BODY_RAW;
import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_CLIENT_IP;
import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_URI_RAW;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MapDataBundleTest {

  @Test
  void threePairsVariant() {
    MapDataBundle bundle =
        MapDataBundle.of(REQUEST_URI_RAW, "/a", REQUEST_CLIENT_IP, "::1", REQUEST_BODY_RAW, "");

    assertEquals(3, bundle.size());
    assertEquals("/a", bundle.get(REQUEST_URI_RAW));
    assertEquals("::1", bundle.get(REQUEST_CLIENT_IP));
    assertEquals("", bundle.get(REQUEST_BODY_RAW));
  }
}
