package com.datadog.profiling.controller.openjdk.events;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmapEntryCacheTest {

  @Test
  void getEvents() throws Exception {
    // SMAPs are only available on Linux
    assumeTrue(OperatingSystem.isLinux());
    // We need at least Java 22 for the annotated regions
    assumeTrue(JavaVirtualMachine.isJavaVersionAtLeast(22));
    SmapEntryCache smapEntryCache =
        new SmapEntryCache(Duration.ofHours(1)); // set up a really long expiration duration
    List<SmapEntryEvent> events1 = smapEntryCache.getEvents();
    List<SmapEntryEvent> events2 = smapEntryCache.getEvents();
    // the cache is using double buffered event list so we can use identity comparison
    assertSame(events1, events2);

    smapEntryCache.invalidate(); // pretend expiring the cache
    events1 = smapEntryCache.getEvents();
    assertNotSame(events1, events2);
  }
}
