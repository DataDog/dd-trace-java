package com.datadog.profiling.context;

import java.util.Arrays;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A utility class to decode a position into [slot, index] coordinates in an array of indexable
 * buffers. It supports each of the indexable buffers having a different size.
 */
public final class PositionDecoder {
  /** Coordinates data holder */
  public static final class Coordinates {
    public final int slot;
    public final int index;

    public Coordinates(int slot, int index) {
      this.slot = slot;
      this.index = index;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Coordinates coordinates = (Coordinates) o;
      return slot == coordinates.slot && index == coordinates.index;
    }

    @Override
    public int hashCode() {
      return Objects.hash(slot, index);
    }

    @Override
    public String toString() {
      return "Coordinates{" + "slot=" + slot + ", index=" + index + '}';
    }
  }

  private static final class Singleton {
    static final PositionDecoder INSTANCE = new PositionDecoder();
  }

  public static PositionDecoder getInstance() {
    return Singleton.INSTANCE;
  }

  /**
   * Turn the position into a {@linkplain Coordinates} instance
   *
   * @param position the position
   * @param bufferBoundaryMap an array of buffer boundaries - a boundary is defined as 'size - 1'
   *     for each buffer element
   * @return decoded {@linkplain Coordinates} or {@literal null}
   */
  @Nullable
  public Coordinates decode(int position, int[] bufferBoundaryMap) {
    return decode(position, bufferBoundaryMap, bufferBoundaryMap.length);
  }

  /**
   * Turn the position into a {@linkplain Coordinates} instance
   *
   * @param position the position
   * @param bufferBoundaryMap an array of buffer boundaries - a boundary is defined as 'size - 1'
   *     for each buffer element; unused slots must have boundary of {@linkplain Integer#MAX_VALUE}
   * @param bufferBoundaryMapLimit limit the buffer boundaries to be used only to the first
   *     {@literal bufferBoundaryMapLimit} ones
   * @return decoded {@linkplain Coordinates} or {@literal null}
   */
  @Nullable
  public Coordinates decode(
      int position, @Nonnull int[] bufferBoundaryMap, int bufferBoundaryMapLimit) {
    if (bufferBoundaryMap.length == 0) {
      return null;
    }
    if (bufferBoundaryMapLimit <= 0 || bufferBoundaryMapLimit > bufferBoundaryMap.length) {
      return null;
    }

    // shortcut for a position falling within the first slot
    if (position <= bufferBoundaryMap[0]) {
      return new Coordinates(0, position);
    }
    // shortcut for positions not covered by the boundary map
    if (position > bufferBoundaryMap[bufferBoundaryMapLimit - 1]) {
      return null;
    }

    // shortcut to linear search for a small number of slots in use
    if (bufferBoundaryMapLimit < 5) {
      int slot = 0;
      while (slot <= bufferBoundaryMapLimit && bufferBoundaryMap[slot] < position) {
        slot++;
      }
      if (slot <= bufferBoundaryMapLimit) {
        return new Coordinates(slot, position - bufferBoundaryMap[slot - 1] - 1);
      }
      return null;
    }

    // use binary search
    int slot = Arrays.binarySearch(bufferBoundaryMap, position);
    if (slot > 0) {
      return new Coordinates(slot, position - bufferBoundaryMap[slot - 1] - 1);
    } else {
      slot = -1 - slot;
      return new Coordinates(slot, position - bufferBoundaryMap[slot - 1] - 1);
    }
  }
}
