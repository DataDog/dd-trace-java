package datadog.trace.api.parsing;

import java.nio.ByteBuffer;
import java.util.BitSet;

public class IndexesOfSearch {

  private final long[] masks;

  public IndexesOfSearch(byte... symbols) {
    this.masks = new long[symbols.length];
    for (int i = 0; i < symbols.length; ++i) {
      masks[i] = ((symbols[i] & 0xFFL) * 0x101010101010101L);
    }
  }

  public BitSet indexesIn(byte[] input) {
    int capacity = (input.length + 7) & -8;
    BitSet positions = new BitSet(capacity);
    int tokensFound = 0;
    int pos = 0;
    ByteBuffer buffer = ByteBuffer.wrap(input);
    for (; pos < (input.length & -8); pos += 8) {
      long word = buffer.getLong(pos);
      long tokens = tag(word);
      tokensFound += Long.bitCount(tokens);
      while (tokens != 0) {
        positions.set(pos + 7 - (Long.numberOfTrailingZeros(tokens) >>> 3));
        tokens &= (tokens - 1);
      }
    }
    if (pos < input.length) {
      long word = 0;
      if (input.length >= 8) {
        word = buffer.getLong(input.length - 8);
      } else {
        for (int i = pos; i < input.length; ++i) {
          word <<= 8;
          word |= (input[i] & 0xFF);
        }
      }
      word <<= ((8 - (input.length - pos)) << 3);
      long tokens = tag(word);
      tokensFound += Long.bitCount(tokens);
      while (tokens != 0) {
        positions.set(pos + 7 - (Long.numberOfTrailingZeros(tokens) >>> 3));
        tokens &= (tokens - 1);
      }
    }
    return tokensFound == 0 ? null : positions;
  }

  private long tag(long word) {
    long tag = 0L;
    for (long mask : masks) {
      tag |= tag(mask, word);
    }
    return tag;
  }

  private long tag(long mask, long word) {
    word ^= mask;
    long holes = (word & 0x7F7F7F7F7F7F7F7FL) + 0x7F7F7F7F7F7F7F7FL;
    return ~(holes | word | 0x7F7F7F7F7F7F7F7FL);
  }
}
