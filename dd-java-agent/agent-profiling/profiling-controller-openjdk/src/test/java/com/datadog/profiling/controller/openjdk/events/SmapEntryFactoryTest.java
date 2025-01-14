package com.datadog.profiling.controller.openjdk.events;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SmapEntryFactoryTest {
  @Test
  void testAnnotatedRegionsSanity() throws Exception {
    try (BufferedReader br = new BufferedReader(new InputStreamReader(Objects.requireNonNull(SmapEntryFactory.class.getResourceAsStream("/smap/annotated_regions.txt"))))) {
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
  void testNextLongValue() throws Exception {
    String line = "3232 fsdasg 1232352523";
    int[] pos = new int[]{0};
    long val = SmapEntryFactory.nextLongValue(line, pos);
    val = SmapEntryFactory.nextLongValue(line, pos);
    val = SmapEntryFactory.nextLongValue(line, pos);
  }
}
