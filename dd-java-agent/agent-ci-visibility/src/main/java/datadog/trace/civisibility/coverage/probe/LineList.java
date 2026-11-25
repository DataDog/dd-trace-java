package datadog.trace.civisibility.coverage.probe;

import java.util.Arrays;

/** Line numbers in the order they were added, without duplicates. */
final class LineList {
  private int[] lines = new int[8];
  private int count;

  void add(int line) {
    if (line < 0 || contains(line)) {
      return;
    }
    if (count == lines.length) {
      lines = Arrays.copyOf(lines, count * 2);
    }
    lines[count++] = line;
  }

  boolean contains(int line) {
    for (int k = 0; k < count; k++) {
      if (lines[k] == line) {
        return true;
      }
    }
    return false;
  }

  int size() {
    return count;
  }

  int[] toArray() {
    return Arrays.copyOf(lines, count);
  }

  void clear() {
    count = 0;
  }
}
