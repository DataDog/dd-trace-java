package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PositionDecoderTest {
  private PositionDecoder instance;

  @BeforeEach
  void setup() {
    instance = PositionDecoder.getInstance();
  }

  @Test
  void decodeInvalidCapacityMap() {
    int[] map = new int[0];

    assertNull(instance.decode(0, map, -1));
    assertNull(instance.decode(0, map, 0));
    assertNull(instance.decode(0, map, 1));
  }

  @Test
  void decodeInvalidCapacityMapSize() {
    int[] map = new int[] {3, 10, 25};

    assertNull(instance.decode(0, map, -1));
    assertNull(instance.decode(0, map, map.length + 1));
  }

  @Test
  void decodeValid() {
    int maxBoundary = 12;
    int[] map = new int[] {2, 4, 6, 8, 10, maxBoundary};
    PositionDecoder.Coordinates[] expected =
        new PositionDecoder.Coordinates[] {
          new PositionDecoder.Coordinates(0, 0),
          new PositionDecoder.Coordinates(0, 1),
          new PositionDecoder.Coordinates(0, 2),
          new PositionDecoder.Coordinates(1, 0),
          new PositionDecoder.Coordinates(1, 1),
          new PositionDecoder.Coordinates(2, 0),
          new PositionDecoder.Coordinates(2, 1),
          new PositionDecoder.Coordinates(3, 0),
          new PositionDecoder.Coordinates(3, 1),
          new PositionDecoder.Coordinates(4, 0),
          new PositionDecoder.Coordinates(4, 1),
          new PositionDecoder.Coordinates(5, 0),
          new PositionDecoder.Coordinates(5, 1)
        };

    for (int mapIndex = map.length - 1; mapIndex >= 0; mapIndex--) {
      int limit = map[mapIndex];
      for (int pos = 0; pos <= limit; pos++) {
        assertEquals(
            expected[pos],
            instance.decode(pos, map, mapIndex + 1),
            "Failed to decode position "
                + pos
                + " with section boundary limit set to "
                + (mapIndex + 1));
      }
    }

    assertNull(instance.decode(map[map.length - 1] + 1, map));
  }

  @Test
  void testDecodeWithPartialBoundaryMap() {
    int[] boundaryMap =
        new int[] {
          31,
          95,
          287,
          863,
          2591,
          7775,
          Integer.MAX_VALUE,
          Integer.MAX_VALUE,
          Integer.MAX_VALUE,
          Integer.MAX_VALUE
        };
    int limit = 5;
    int pos = 324 * 8;
    PositionDecoder.Coordinates expectedCoordinates = new PositionDecoder.Coordinates(5, 0);

    PositionDecoder.Coordinates decoded = instance.decode(pos, boundaryMap, limit + 1);
    assertEquals(expectedCoordinates, decoded);
  }
}
