package datadog.trace.util;

import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableList;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableMap;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableSet;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CollectionUtilsTest {

  @Test
  void immutableCopyOfSetCreatedWhenThePlatformPermits() {
    Set<String> set = new HashSet<>(Arrays.asList("x", "y", "z"));
    String expectedClassName =
        isJavaVersionAtLeast(10) ? "java.util.ImmutableCollections" : set.getClass().getName();

    Set<String> hopefullyImmutable = tryMakeImmutableSet(set);

    assertTrue(hopefullyImmutable.getClass().getName().contains(expectedClassName));
  }

  @Test
  void immutableCopyOfListCreatedWhenThePlatformPermits() {
    Set<String> set = new HashSet<>(Arrays.asList("x", "y", "z"));
    String expectedClassName =
        isJavaVersionAtLeast(10) ? "java.util.ImmutableCollections" : ArrayList.class.getName();

    List<String> hopefullyImmutable = tryMakeImmutableList(set);

    assertTrue(hopefullyImmutable.getClass().getName().contains(expectedClassName));
  }

  @Test
  void immutableCopyOfMapCreatedWhenThePlatformPermits() {
    Map<String, String> map = new HashMap<>();
    map.put("x", "x");
    map.put("y", "y");
    map.put("z", "z");
    String expectedClassName =
        isJavaVersionAtLeast(10) ? "java.util.ImmutableCollections" : map.getClass().getName();

    Map<String, String> hopefullyImmutable = tryMakeImmutableMap(map);

    assertTrue(hopefullyImmutable.getClass().getName().contains(expectedClassName));
  }
}
