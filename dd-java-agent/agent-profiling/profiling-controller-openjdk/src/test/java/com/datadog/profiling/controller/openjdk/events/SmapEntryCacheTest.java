package com.datadog.profiling.controller.openjdk.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SmapEntryCacheTest {

  @ParameterizedTest
  @ValueSource(strings = {"UTF-8", "ISO-8859-1"})
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void readsPathnameWithNativeCharset(String charsetName, @TempDir Path directory)
      throws Exception {
    String previousEncoding = System.getProperty("native.encoding");
    try {
      System.setProperty("native.encoding", charsetName);
      String pathname = "/tmp/café.so";
      String contents = "00400000-00401000 r-xp 00000000 08:02 1234 " + pathname + "\nSize: 4 kB\n";
      Path smaps = directory.resolve("smaps");
      Files.write(smaps, contents.getBytes(Charset.forName(charsetName)));

      SmapEntryCache cache = new SmapEntryCache(Duration.ofHours(1), smaps);
      cache.invalidate();
      List<SmapEntryEvent> events = cache.getEvents();

      assertEquals(1, events.size());
      assertEquals(pathname, events.get(0).pathname);
    } finally {
      if (previousEncoding == null) {
        System.clearProperty("native.encoding");
      } else {
        System.setProperty("native.encoding", previousEncoding);
      }
    }
  }

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
