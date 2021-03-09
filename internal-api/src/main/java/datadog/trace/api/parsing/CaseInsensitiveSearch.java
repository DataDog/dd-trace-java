package datadog.trace.api.parsing;

import java.nio.charset.StandardCharsets;

public final class CaseInsensitiveSearch {

  private final long[] low = new long[16];
  private final long[] high = new long[16];

  private final long success;

  public CaseInsensitiveSearch(String term) {
    this(term.getBytes(StandardCharsets.UTF_8));
  }

  public CaseInsensitiveSearch(byte[] term) {
    long pattern = 1L;
    for (byte b : term) {
      low[b & 0xF] |= pattern;
      high[(b >>> 4) & 0xF] |= pattern;
      if (Character.isAlphabetic(b)) {
        high[((b ^ 0x20) >>> 4) & 0xF] |= pattern;
      }
      pattern <<= 1;
    }
    this.success = 1L << (term.length - 1);
  }

  public int find(byte[] input, int start, int end) {
    long current = 0L;
    for (int i = Math.max(start, 0); i < Math.min(end, input.length); ++i) {
      long highMask = high[(input[i] >>> 4) & 0xF];
      long lowMask = low[input[i] & 0xF];
      current = ((current << 1) | 1) & highMask & lowMask;
      if ((current & success) == success) {
        return i - Long.numberOfTrailingZeros(success);
      }
    }
    return -1;
  }

  public int find(byte[] input) {
    return find(input, 0, input.length);
  }
}
