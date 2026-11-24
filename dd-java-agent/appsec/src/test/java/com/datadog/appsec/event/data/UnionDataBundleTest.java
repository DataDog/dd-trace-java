package com.datadog.appsec.event.data;

import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_BODY_OBJECT;
import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_SCHEME;
import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_URI_RAW;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class UnionDataBundleTest {

  private final DataBundle dataBundle =
      DataBundle.unionOf(
          new SingletonDataBundle<>(REQUEST_URI_RAW, "/"),
          new SingletonDataBundle<>(REQUEST_SCHEME, "http"));

  @Test
  void sizeAndAllAddresses() {
    assertEquals(2, dataBundle.size());
    assertEquals(asList(REQUEST_URI_RAW, REQUEST_SCHEME), dataBundle.getAllAddresses());
    assertEquals("/", dataBundle.get(REQUEST_URI_RAW));
    assertEquals("http", dataBundle.get(REQUEST_SCHEME));
    assertTrue(dataBundle.hasAddress(REQUEST_URI_RAW));
    assertTrue(dataBundle.hasAddress(REQUEST_SCHEME));
    assertFalse(dataBundle.hasAddress(REQUEST_BODY_OBJECT));
  }

  @Test
  void iteration() {
    // setup
    Iterator<Map.Entry<Address<?>, Object>> iterator = dataBundle.iterator();

    // expect
    assertTrue(iterator.hasNext());
    Map.Entry<Address<?>, Object> first = iterator.next();
    assertEquals(REQUEST_URI_RAW, first.getKey());
    assertEquals("/", first.getValue());

    assertTrue(iterator.hasNext());
    Map.Entry<Address<?>, Object> second = iterator.next();
    assertEquals(REQUEST_SCHEME, second.getKey());
    assertEquals("http", second.getValue());

    assertFalse(iterator.hasNext());

    // when / then
    assertThrows(NoSuchElementException.class, iterator::next);
  }
}
