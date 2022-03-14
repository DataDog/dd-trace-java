package com.datadog.profiling.context;

import java.util.Arrays;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class PositionDecoder {
  public static final class Position {
    public final int slot;
    public final int index;

    public Position(int slot, int index) {
      this.slot = slot;
      this.index = index;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Position position = (Position) o;
      return slot == position.slot && index == position.index;
    }

    @Override
    public int hashCode() {
      return Objects.hash(slot, index);
    }
  }

  private static final class Singleton {
    static final PositionDecoder INSTANCE = new PositionDecoder();
  }

  public static PositionDecoder getInstance() {
    return Singleton.INSTANCE;
  }

  public Position decode(int index, int[] sectionBoundaryMap) {
    return decode(index, sectionBoundaryMap, sectionBoundaryMap.length);
  }

  @Nullable
  public Position decode(
      int index, @Nonnull int[] sectionBoundaryMap, int sectionBoundaryMapLimit) {
    if (sectionBoundaryMap.length == 0) {
      return null;
    }
    if (sectionBoundaryMapLimit < 0 || sectionBoundaryMapLimit > sectionBoundaryMap.length) {
      return null;
    }

    // shortcut for an index falling within the first slot
    if (index <= sectionBoundaryMap[0]) {
      return new Position(0, index);
    }

    // shortcut to linear search for a small number of slots in use
    if (sectionBoundaryMapLimit < 5) {
      int slot = 0;
      while (slot <= sectionBoundaryMapLimit && sectionBoundaryMap[slot] < index) {
        slot++;
      }
      if (slot <= sectionBoundaryMapLimit) {
        return slot > 0
            ? new Position(slot, index - sectionBoundaryMap[slot - 1] - 1)
            : new Position(slot, index);
      }
      return null;
    }

    // use binary search
    int slot = Arrays.binarySearch(sectionBoundaryMap, index);
    if (slot > 0) {
      return new Position(slot, index - sectionBoundaryMap[slot - 1] - 1);
    } else if (slot == 0) {
      return new Position(slot, index);
    } else {
      slot = -1 - slot;
      if (slot <= sectionBoundaryMapLimit) {
        return slot > 0
            ? new Position(slot, index - sectionBoundaryMap[slot - 1] - 1)
            : new Position(slot, index);
      }
      return null;
    }
  }
}
