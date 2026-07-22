package datadog.trace.test.junit.utils.tabletest;

import java.util.BitSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;

/**
 * Converts a TableTest cell into the heterogeneous {@code value} accepted by {@code
 * ConfigSetting.of}. Use with {@code @ConvertWith(ConfigValueConverter.class)} on an {@code
 * Object}-typed parameter that needs to be a list, map, or {@link BitSet}.
 *
 * <p>Lists ({@code [a, b, c]}) and maps ({@code [key: value]}) are parsed natively by TableTest.
 * The only case needing a typed value is {@link BitSet}.
 *
 * <p>BitSet syntax: {@code bits(<token>, ...)} where each token is either a single bit ({@code 33})
 * or a half-open interval ({@code 200-300}, i.e. bits 200..299). This mirrors how {@code
 * ConfigSetting} renders integer ranges, so the cell literal reads the same as the expected output.
 */
public class ConfigValueConverter implements ArgumentConverter {

  private static final String BITSET_PREFIX = "bits(";
  private static final Pattern COMMA = Pattern.compile(",");

  @Override
  public Object convert(Object source, ParameterContext context)
      throws ArgumentConversionException {
    if (source == null) return null;
    if (source instanceof String) {
      String s = ((String) source).trim();
      if (s.startsWith(BITSET_PREFIX) && s.endsWith(")")) {
        return parseBitSet(s.substring(BITSET_PREFIX.length(), s.length() - 1));
      }
    }
    // Lists and maps are already parsed by TableTest
    return source;
  }

  private static BitSet parseBitSet(String intervals) {
    BitSet bitSet = new BitSet();
    for (String token : COMMA.split(intervals)) {
      String trimmed = token.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int dash = trimmed.indexOf('-');
      if (dash > 0) {
        int start = Integer.parseInt(trimmed.substring(0, dash).trim());
        int end = Integer.parseInt(trimmed.substring(dash + 1).trim());
        bitSet.set(start, end);
      } else {
        bitSet.set(Integer.parseInt(trimmed));
      }
    }
    return bitSet;
  }
}
