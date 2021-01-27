package datadog.trace.api.normalize;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;

public final class SQLNormalizer {

  private static final long QUOTES = pattern((byte) '\'');
  private static final long ESCAPES = pattern((byte) '\\');

  public static UTF8BytesString normalize(UTF8BytesString sql) {
    int encodedLength = sql.encodedLength();
    int capacity = roundUp(encodedLength, 8);
    ByteBuffer buffer = ByteBuffer.allocate(capacity);
    sql.transferTo(buffer);
    buffer.position(0);
    buffer.limit(capacity);
    BitSet quotes = new BitSet(roundUp(buffer.capacity() / 64, 64));
    int pos = 0;
    long escapes = 0L;
    int matches = 0;
    while (buffer.hasRemaining()) {
      long word = buffer.getLong();
      long escapesInWord = tag(word, ESCAPES);
      // prevents escaped quotes from being marked
      word |= (escapes | (escapesInWord >>> 8));
      // contains the positions of all the unescaped quotes in the word
      long quoteTags = tag(word, QUOTES);
      while (quoteTags != 0) {
        quotes.set(pos + 7 - (Long.numberOfTrailingZeros(quoteTags) >>> 3));
        quoteTags &= (quoteTags - 1);
        ++matches;
      }
      pos += 8;
      // rotate
      escapes = escapesInWord << 56;
    }
    // invalid case - need pairs of quotes
    if ((matches & 1) == 1) {
      return sql;
    }
    byte[] array = buffer.array();
    int end = quotes.previousSetBit(capacity) - 1;
    int outputLength = encodedLength;
    while (end > 0) {
      int start = quotes.previousSetBit(end);
      System.arraycopy(array, end + 1, array, start, outputLength - end - 1);
      array[start] = (byte) '?';
      outputLength -= (end - start + 1);
      end = quotes.previousSetBit(start - 1) - 1;
    }
    return UTF8BytesString.create(Arrays.copyOf(array, outputLength));
  }

  private static long tag(long pattern, long word) {
    word ^= pattern;
    long holes = (word & 0x7F7F7F7F7F7F7F7FL) + 0x7F7F7F7F7F7F7F7FL;
    return ~(holes | word | 0x7F7F7F7F7F7F7F7FL);
  }

  private static long pattern(byte symbol) {
    return (symbol & 0xFFL) * 0x101010101010101L;
  }

  private static int roundUp(int value, int multiple) {
    return (value + multiple - 1) & -multiple;
  }
}
