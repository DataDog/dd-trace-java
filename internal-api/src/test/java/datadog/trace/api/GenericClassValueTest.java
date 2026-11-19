package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class GenericClassValueTest {

  @Test
  void evaluateFunction() {
    ClassValue<String> classValue = GenericClassValue.of(Class::getSimpleName);

    assertEquals(ConcurrentHashMap.class.getSimpleName(), classValue.get(ConcurrentHashMap.class));
    assertEquals(String.class.getSimpleName(), classValue.get(String.class));
  }

  @Test
  void constructsProducesDifferentInstances() {
    ClassValue<ConcurrentHashMap<?, ?>> classValue =
        GenericClassValue.constructing(ConcurrentHashMap.class);

    assertNotSame(classValue.get(ConcurrentHashMap.class), classValue.get(String.class));
  }
}
