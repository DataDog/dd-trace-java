package datadog.trace.api.normalize;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;

public final class SQLNormalizer {

  private static final long QUOTES = pattern((byte) '\'');
  private static final long ESCAPES = pattern((byte) '\\');

  public static UTF8BytesString normalize(String sql) {
    byte[] utf8 = sql.getBytes(UTF_8);
    int capacity = (utf8.length + 7) & -8;
    BitSet quotes = new BitSet(capacity);
    long escapes = 0L;
    int matches = 0;
    int pos = 0;
    ByteBuffer buffer = ByteBuffer.wrap(utf8);
    for (; pos < (utf8.length & -8); pos += 8) {
      long word = buffer.getLong(pos);
      long escapesInWord = tag(word, ESCAPES);
      // prevents escaped quotes from being marked
      word |= (escapes | (escapesInWord >>> 8));
      // contains the positions of all the unescaped quotes in the word
      long quoteTags = tag(word, QUOTES);
      matches += Long.bitCount(quoteTags);
      while (quoteTags != 0) {
        quotes.set(pos + 7 - (Long.numberOfTrailingZeros(quoteTags) >>> 3));
        quoteTags &= (quoteTags - 1);
      }
      // rotate
      escapes = escapesInWord << 56;
    }
    if (pos < utf8.length) {
      long word = buffer.getLong(utf8.length - 8);
      word <<= ((8 - (utf8.length - pos)) << 3);
      long escapesInWord = tag(word, ESCAPES);
      // prevents escaped quotes from being marked
      word |= (escapes | (escapesInWord >>> 8));
      // contains the positions of all the unescaped quotes in the word
      long quoteTags = tag(word, QUOTES);
      matches += Long.bitCount(quoteTags);
      while (quoteTags != 0) {
        quotes.set(pos + 7 - (Long.numberOfTrailingZeros(quoteTags) >>> 3));
        quoteTags &= (quoteTags - 1);
      }
    }
    // invalid case - need pairs of quotes
    if ((matches & 1) == 1 || matches == 0) {
      return UTF8BytesString.create(sql, utf8);
    }
    int end = quotes.previousSetBit(capacity) - 1;
    int outputLength = utf8.length;
    while (end > 0) {
      int start = quotes.previousSetBit(end);
      System.arraycopy(utf8, end + 1, utf8, start, outputLength - end - 1);
      utf8[start] = (byte) '?';
      outputLength -= (end - start + 1);
      end = quotes.previousSetBit(start - 1) - 1;
    }
    return UTF8BytesString.create(Arrays.copyOf(utf8, outputLength));
  }

  private static long tag(long pattern, long word) {
    word ^= pattern;
    long holes = (word & 0x7F7F7F7F7F7F7F7FL) + 0x7F7F7F7F7F7F7F7FL;
    return ~(holes | word | 0x7F7F7F7F7F7F7F7FL);
  }

  private static long pattern(byte symbol) {
    return (symbol & 0xFFL) * 0x101010101010101L;
  }
}
