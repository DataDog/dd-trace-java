package com.datadog.appsec.event.data;

import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_BODY_OBJECT;
import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_CLIENT_IP;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class SingletonDataBundleTest {

  private final DataBundle dataBundle = new SingletonDataBundle<>(REQUEST_BODY_OBJECT, "foo");

  @Test
  void basicMethods() {
    assertTrue(dataBundle.hasAddress(REQUEST_BODY_OBJECT));
    assertFalse(dataBundle.hasAddress(REQUEST_CLIENT_IP));
    assertEquals(singletonList(REQUEST_BODY_OBJECT), dataBundle.getAllAddresses());
    assertEquals("foo", dataBundle.get(REQUEST_BODY_OBJECT));
    assertNull(dataBundle.get(REQUEST_CLIENT_IP));
    assertEquals(1, dataBundle.size());
  }

  @Test
  void iteration() {
    for (Map.Entry<Address<?>, Object> entry : dataBundle) {
      assertEquals(REQUEST_BODY_OBJECT, entry.getKey());
      assertEquals("foo", entry.getValue());
    }
  }

  @Test
  void iterationRelatedExceptions() {
    // when
    Iterator<Map.Entry<Address<?>, Object>> iterator = dataBundle.iterator();
    Map.Entry<Address<?>, Object> entry = iterator.next();

    // then
    assertThrows(UnsupportedOperationException.class, () -> entry.setValue("should throw"));

    // when / then
    assertThrows(NoSuchElementException.class, iterator::next);
  }
}
