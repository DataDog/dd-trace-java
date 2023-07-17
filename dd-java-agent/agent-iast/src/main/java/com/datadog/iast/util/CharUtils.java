package com.datadog.iast.util;

import java.util.Arrays;

public abstract class CharUtils {
  private CharUtils() {}

  /** Builds a new string of a given length using the buffer as a source of values. */
  public static String newString(final int length, final char[] buffer) {
    final StringBuilder result = new StringBuilder(length);
    int remaining = length;
    while (remaining > 0) {
      final int next = Math.min(remaining, buffer.length);
      result.append(buffer, 0, next);
      remaining -= next;
    }
    return result.toString();
  }

  /** Creates a new array filled with the given value. */
  public static char[] newCharArray(int size, final char value) {
    final char[] result = new char[size];
    Arrays.fill(result, value);
    return result;
  }

  /** Fills the given array with the range of characters and returns the number of items written. */
  public static int fillCharArray(
      final int offset, final char start, final char end, final char[] result) {
    int index = offset;
    for (int i = start; i <= end; i++) {
      result[index++] = (char) i;
    }
    return index - offset;
  }
}
