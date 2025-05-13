package com.datadog.profiling.controller.openjdk.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

public class SmapEntryFactoryTest {
  @Test
  void testAnnotatedRegionsSanity() throws Exception {
    try (BufferedReader br =
        new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(
                    SmapEntryFactory.class.getResourceAsStream("/smap/annotated_regions.txt"))))) {
      String line = null;
      while ((line = br.readLine()) != null) {
        SmapEntryFactory.AnnotatedRegion region = SmapEntryFactory.fromAnnotatedEntry(line);
        if (line.startsWith("0x")) {
          assertNotNull(region);
        }
      }
    }
  }

  @Test
  void testSmapSanity() throws Exception {
    try (BufferedReader br =
        new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(
                    SmapEntryFactory.class.getResourceAsStream("/smap/smaps.txt"))))) {
      List<SmapEntryEvent> events = SmapEntryFactory.readEvents(br);
      assertNotNull(events);
      assertEquals(747, events.size());
    }
  }
}
