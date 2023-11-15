package datadog.trace.api.normalize;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Arrays;
import java.util.BitSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class removes numbers and SQL literals from strings on a best-effort basis, producing UTF-8
 * encoded bytes. The aim is to remove as much information as possible, but only when it's cheap to
 * do so. It makes no context-sensitive decisions, which works well for ANSI SQL, but, for example,
 * will not remove literals in MySQL which are indistinguishable from object names. This is not an
 * obfuscator, and the strings produced by this class must be passed through obfuscation in the
 * trace agent.
 */
public final class SQLNormalizer {

  private static final Logger log = LoggerFactory.getLogger(SQLNormalizer.class);

  private static final BitSet NUMERIC_LITERAL_PREFIX = new BitSet();
  private static final BitSet SPLITTERS = new BitSet();

  static {
    for (byte symbol :
        new byte[] {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-', '+', '.'}) {
      int unsigned = symbol & 0xFF;
      NUMERIC_LITERAL_PREFIX.set(unsigned);
    }
    for (byte symbol : new byte[] {',', '(', ')', '|'}) {
      int unsigned = symbol & 0xFF;
      SPLITTERS.set(unsigned);
    }
    for (int i = 0; i < 256; ++i) {
      if (Character.isWhitespace((char) i)) {
        SPLITTERS.set(i);
      }
    }
  }

  public static UTF8BytesString normalizeCharSequence(CharSequence sql) {
    return normalize(sql.toString());
  }

  public static UTF8BytesString normalize(String sql) {
    byte[] utf8 = sql.getBytes(UTF_8);
    try {
      BitSet splitters = findSplitterPositions(utf8);
      int outputLength = utf8.length;
      int end = outputLength;
      int start = end > 0 ? splitters.previousSetBit(end - 1) : -1;
      boolean modified = false;
      // strip out anything ending with a quote (covers string and hex literals)
      // or anything starting with a number, a quote, a decimal point, or a sign
      while (end > 0 && start > 0) {
        int sequenceStart = start + 1;
        int sequenceEnd = end - 1;
        if (sequenceEnd == sequenceStart) {
          // single digit numbers can can be fixed in place
          if (Character.isDigit(utf8[sequenceStart])) {
            utf8[sequenceStart] = '?';
            modified = true;
          }
        } else if (sequenceStart < sequenceEnd) {
          if (isQuoted(utf8, sequenceStart, sequenceEnd)
              || isNumericLiteralPrefix(utf8, sequenceStart)
              || isHexLiteralPrefix(utf8, sequenceStart, sequenceEnd)) {
            int length = sequenceEnd - sequenceStart;
            System.arraycopy(utf8, end, utf8, sequenceStart + 1, outputLength - end);
            utf8[sequenceStart] = '?';
            outputLength -= length;
            modified = true;
          }
        }
        end = start;
        start = splitters.previousSetBit(start - 1);
      }
      if (modified) {
        return UTF8BytesString.create(Arrays.copyOf(utf8, outputLength));
      }
    } catch (Throwable paranoid) {
      log.debug("Error normalizing sql {}", sql, paranoid);
    }
    return UTF8BytesString.create(sql, utf8);
  }

  private static boolean isQuoted(byte[] utf8, int start, int end) {
    return (utf8[start] == '\'' && utf8[end] == '\'');
  }

  private static boolean isHexLiteralPrefix(byte[] utf8, int start, int end) {
    return (utf8[start] | ' ') == 'x' && start + 1 < end && utf8[start + 1] == '\'';
  }

  private static boolean isNumericLiteralPrefix(byte[] utf8, int start) {
    return NUMERIC_LITERAL_PREFIX.get(utf8[start] & 0xFF)
        // preserve single line comment (--) prefixes
        && !(utf8[start + 1] == '-' && utf8[start] == '-');
  }

  private static boolean isSplitter(byte symbol) {
    return SPLITTERS.get(symbol & 0xFF);
  }

  private static BitSet findSplitterPositions(byte[] utf8) {
    BitSet positions = new BitSet(utf8.length);
    boolean quoted = false;
    boolean escaped = false;
    for (int i = 0; i < utf8.length; ++i) {
      byte b = utf8[i];
      if (b == '\'' && !escaped) {
        quoted = !quoted;
      } else {
        escaped = (b == '\\') & !escaped;
        positions.set(i, !quoted & isSplitter(b));
      }
    }
    return positions;
  }
}
