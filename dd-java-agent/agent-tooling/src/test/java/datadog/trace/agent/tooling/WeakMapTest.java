package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.WeakMaps.CLEAN_FREQUENCY_SECONDS;
import static datadog.trace.test.util.GCUtils.awaitGC;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.bootstrap.WeakMap;
import datadog.trace.test.util.DDJavaSpecification;
import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

class WeakMapTest extends DDJavaSpecification {

  @Test
  void weakMapAcceptsNullValues() {
    WeakMap<String, String> map = WeakMaps.newWeakMap();

    assertDoesNotThrow(() -> map.put("key", null));
  }

  @Test
  void callingNewWeakMapCreatesIndependentMaps() {
    Object key = new Object();
    WeakMap<Object, String> map1 = WeakMaps.newWeakMap();
    WeakMap<Object, String> map2 = WeakMaps.newWeakMap();

    // when
    map1.put(key, "value1");
    map2.put(key, "value2");

    // then
    assertEquals("value1", map1.get(key));
    assertEquals("value2", map2.get(key));
  }

  @Test
  @DisabledIfSystemProperty(
      named = "java.runtime.name",
      matches = ".*Semeru.*",
      disabledReason = "Often fails in Semeru runtime")
  void unreferencedMapGetsCleanedUp() throws InterruptedException {
    WeakMap<Object, Object> map = WeakMaps.newWeakMap();
    WeakReference<WeakMap<Object, Object>> mapRef = new WeakReference<>(map);

    // when
    map = null;
    awaitGC(mapRef);

    // then
    assertNull(mapRef.get());
  }

  @Test
  @DisabledIfSystemProperty(
      named = "java.runtime.name",
      matches = ".*Semeru.*",
      disabledReason = "Often fails in Semeru runtime")
  void unreferencedKeysGetCleanedUp() throws InterruptedException {
    Object key = new Object();
    WeakMap<Object, String> map = WeakMaps.newWeakMap();
    map.put(key, "value");
    awaitGC();

    // expect
    assertEquals(1, map.size());

    // when
    WeakReference<Object> keyRef = new WeakReference<>(key);
    key = null;
    awaitGC(keyRef);

    // Sleep enough time for cleanup thread to get scheduled.
    // But on a very slow box (or high load) scheduling may not be exactly predictable
    // so we try a few times.
    int count = 0;
    while (map.size() != 0 && count < 10) {
      Thread.sleep(TimeUnit.SECONDS.toMillis(CLEAN_FREQUENCY_SECONDS));
      count++;
    }

    // then
    assertEquals(0, map.size());
  }
}
