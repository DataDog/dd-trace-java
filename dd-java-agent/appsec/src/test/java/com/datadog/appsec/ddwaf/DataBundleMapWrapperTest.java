package com.datadog.appsec.ddwaf;

import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_CLIENT_IP;
import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_URI_RAW;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.MapDataBundle;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DataBundleMapWrapperTest extends DDJavaSpecification {

  private Map<String, Object> mapWrapper;

  @BeforeEach
  void setup() {
    // use ofDelegate with LinkedHashMap to ensure iteration order
    Map<Address<?>, Object> data = new LinkedHashMap<>();
    data.put(REQUEST_URI_RAW, "/b");
    data.put(REQUEST_CLIENT_IP, "::1");
    DataBundle dataBundle = MapDataBundle.ofDelegate(data);
    mapWrapper =
        new WAFModule.DataBundleMapWrapper(
            // REQUEST_CLIENT_IP will be filtered into an empty map
            singletonList(REQUEST_URI_RAW), dataBundle);
  }

  @Test
  void size() {
    // expect
    assertEquals(2, mapWrapper.size());
  }

  @Test
  void entrySet() {
    Iterator<Map.Entry<String, Object>> iter = mapWrapper.entrySet().iterator();

    // expect
    assertTrue(iter.hasNext());

    // when
    Map.Entry<String, Object> elem = iter.next();

    // then
    assertEquals(REQUEST_URI_RAW.getKey(), elem.getKey());
    assertEquals("/b", elem.getValue());

    // when / then
    Map.Entry<String, Object> firstElem = elem;
    assertThrows(UnsupportedOperationException.class, () -> firstElem.setValue("foo"));

    // when
    elem = iter.next();

    // then
    assertEquals(REQUEST_CLIENT_IP.getKey(), elem.getKey());
    assertEquals(emptyMap(), elem.getValue());
    assertFalse(iter.hasNext());

    // when / then
    assertThrows(UnsupportedOperationException.class, iter::remove);

    // when / then
    assertThrows(NoSuchElementException.class, iter::next);
  }

  @Test
  void entrySetResultSupportsOnlyIterator() {
    Set<Map.Entry<String, Object>> entrySet = mapWrapper.entrySet();

    // when / then
    assertThrows(UnsupportedOperationException.class, entrySet::size);
  }

  @ParameterizedTest(name = "methods other than entrySet and size are not supported: {0}")
  @MethodSource("methodsOtherThanEntrySetAndSizeAreNotSupportedArguments")
  void methodsOtherThanEntrySetAndSizeAreNotSupported(
      String methodName, Consumer<Map<String, Object>> method) {
    // when / then
    assertThrows(UnsupportedOperationException.class, () -> method.accept(mapWrapper));
  }

  static Stream<Arguments> methodsOtherThanEntrySetAndSizeAreNotSupportedArguments() {
    return Stream.of(
        mapMethod("values", Map::values),
        mapMethod("keySet", Map::keySet),
        mapMethod("clean", Map::clear),
        mapMethod("empty", Map::isEmpty),
        mapMethod("putAll", map -> map.putAll(emptyMap())),
        mapMethod("remove", map -> map.remove("a")),
        mapMethod("put", map -> map.put("b", "a")),
        mapMethod("get", map -> map.get("a")),
        mapMethod("containsValue", map -> map.containsValue("b")),
        mapMethod("containsKey", map -> map.containsKey("a")));
  }

  private static Arguments mapMethod(String methodName, Consumer<Map<String, Object>> method) {
    return arguments(methodName, method);
  }
}
