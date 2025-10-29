package com.datadog.debugger.symbol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SymbolExtractorTest {

  @Test
  void ranges() {
    List<Scope.LineRange> ranges =
        SymbolExtractor.buildRanges(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
    assertEquals(1, ranges.size());
    assertEquals(1, ranges.get(0).start);
    assertEquals(10, ranges.get(0).end);
    ranges = SymbolExtractor.buildRanges(Arrays.asList(1, 3, 5, 7, 9));
    assertEquals(5, ranges.size());
    assertLineRange(ranges.get(0), 1, 1);
    assertLineRange(ranges.get(1), 3, 3);
    assertLineRange(ranges.get(2), 5, 5);
    assertLineRange(ranges.get(3), 7, 7);
    assertLineRange(ranges.get(4), 9, 9);
    ranges = SymbolExtractor.buildRanges(Arrays.asList(1, 2, 4, 5, 7, 9, 10));
    assertEquals(4, ranges.size());
    assertLineRange(ranges.get(0), 1, 2);
    assertLineRange(ranges.get(1), 4, 5);
    assertLineRange(ranges.get(2), 7, 7);
    assertLineRange(ranges.get(3), 9, 10);
  }

  private static void assertLineRange(Scope.LineRange range, int expectedStart, int expectedEnd) {
    assertEquals(expectedStart, range.start);
    assertEquals(expectedEnd, range.end);
  }
}
