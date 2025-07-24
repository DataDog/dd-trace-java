package com.datadog.profiling.controller.openjdk.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class SmapEntryFactoryTest {
  @ParameterizedTest
  @ValueSource(ints = {23, 24})
  void testAnnotatedRegionsSanity(int javaVersion) throws Exception {
    try (BufferedReader br =
        new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(
                    SmapEntryFactory.class.getResourceAsStream(
                        "/smap/annotated_regions_" + javaVersion + ".txt"))))) {

      long sentinel = 0x1000000420000000L;
      String line = null;
      Set<String> descs = new HashSet<>();
      boolean sentinelFound = false;

      while ((line = br.readLine()) != null) {
        SmapEntryCache.AnnotatedRegion region =
            SmapEntryCache.fromAnnotatedEntry(line, javaVersion);
        if (line.startsWith("0x")) {
          assertNotNull(region);
          descs.add(region.description);
          sentinelFound |= region.startAddress == sentinel;
        }
      }
      assertTrue(sentinelFound, "Sentinel address not found");
      assertTrue(descs.contains("JAVAHEAP"), "JAVAHEAP not found");
      assertTrue(descs.contains("GC"), "GC not found");
      assertTrue(descs.contains("META"), "META not found");
      assertTrue(descs.contains("STACK"), "STACK not found");
      assertTrue(descs.contains("UNDEFINED"), "UNDEFINED not found");
    }
  }

  @Test
  void testSmapSanity() throws Exception {
    try (BufferedReader br =
        new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(
                    SmapEntryFactory.class.getResourceAsStream("/smap/smaps.txt"))))) {
      List<SmapEntryEvent> events = new ArrayList<>();
      SmapEntryCache.readEvents(br, events);
      assertNotNull(events);
      assertEquals(747, events.size());
    }
  }
}
