package datadog.trace.junit.utils.tabletest;

import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;

/**
 * Converts a String cell value to the most specific boxed Java primitive type. Use with
 * {@code @ConvertWith(BoxedValueConverter.class)} on {@code Object}-typed parameters when the test
 * needs actual typed values (e.g. {@code Float} not {@code String "2.33f"}).
 *
 * <p>Conversion rules:
 *
 * <ul>
 *   <li>blank/null -> null
 *   <li>{@code "true"}/{@code "false"} -> {@link Boolean}
 *   <li>ends with {@code "f"} -> {@link Float}
 *   <li>contains {@code "."} -> {@link Double}
 *   <li>parseable as integer -> {@link Integer}
 *   <li>otherwise -> {@link String}
 * </ul>
 */
public class BoxedValueConverter implements ArgumentConverter {

  @Override
  public Object convert(Object source, ParameterContext context)
      throws ArgumentConversionException {
    if (source == null) return null;
    String s = source.toString();
    if (s.isEmpty()) return null;
    if ("true".equals(s)) return Boolean.TRUE;
    if ("false".equals(s)) return Boolean.FALSE;
    if (s.endsWith("f")) {
      try {
        return Float.parseFloat(s.substring(0, s.length() - 1));
      } catch (NumberFormatException ignored) {
      }
    }
    if (s.contains(".")) {
      try {
        return Double.parseDouble(s);
      } catch (NumberFormatException ignored) {
      }
    }
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException ignored) {
    }
    return s;
  }
}
