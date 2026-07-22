package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.test.junit.utils.tabletest.ConfigValueConverter;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

public class ConfigSettingTest {

  @TableTest({
    "scenario         | key1 | value1 | origin1  | key2 | value2 | origin2  | expectedEqual",
    "equal            | key  | value  | DEFAULT  | key  | value  | DEFAULT  | true         ",
    "different key    | key  | value  | ENV      | key2 | value  | ENV      | false        ",
    "different value  | key  | value2 | JVM_PROP | key  | value  | JVM_PROP | false        ",
    "different origin | key  | value  | ENV      | key  | value  | DEFAULT  | false        "
  })
  void supportsEqualityCheck(
      String key1,
      Object value1,
      ConfigOrigin origin1,
      String key2,
      Object value2,
      ConfigOrigin origin2,
      boolean expectedEqual) {
    ConfigSetting cs1 = ConfigSetting.of(key1, value1, origin1);
    ConfigSetting cs2 = ConfigSetting.of(key2, value2, origin2);

    if (expectedEqual) {
      assertEquals(cs1.hashCode(), cs2.hashCode());
      assertEquals(cs1, cs2);
      assertEquals(cs2, cs1);
      assertEquals(cs1.toString(), cs2.toString());
    } else {
      assertNotEquals(cs1.hashCode(), cs2.hashCode());
      assertNotEquals(cs1, cs2);
      assertNotEquals(cs2, cs1);
      assertNotEquals(cs1.toString(), cs2.toString());
    }
  }

  @TableTest({
    "scenario             | key                  | value     | filteredValue",
    "DD_API_KEY           | DD_API_KEY           | somevalue | <hidden>     ",
    "dd.api-key           | dd.api-key           | somevalue | <hidden>     ",
    "dd.profiling.api-key | dd.profiling.api-key | somevalue | <hidden>     ",
    "dd.profiling.apikey  | dd.profiling.apikey  | somevalue | <hidden>     ",
    "some.other.key       | some.other.key       | somevalue | somevalue    "
  })
  void filtersKeyValues(String key, String value, String filteredValue) {
    assertEquals(filteredValue, ConfigSetting.of(key, value, ConfigOrigin.DEFAULT).stringValue());
  }

  @TableTest({
    "scenario | value  | rendered",
    "null     |        |         ",
    "boolean  | true   | true    ",
    "boolean  | false  | false   ",
    "integer  | 1      | 1       ",
    "double   | 1.0    | 1.0     ",
    "float    | 2.33f  | 2.33    ",
    "string   | string | string  "
  })
  void supportBasicTypes(@ConvertWith(BoxedValueConverter.class) Object value, String rendered) {
    assertEquals(rendered, ConfigSetting.of("key", value, ConfigOrigin.DEFAULT).stringValue());
  }

  @TableTest({
    "scenario      | value                           | rendered              ",
    "iterable      | [1, 2, 3]                       | 1,2,3                 ",
    "decimals      | [1.0, 22.23, 3.1415]            | 1.0,22.23,3.1415      ",
    "map           | [a: 1, b: 2]                    | a:1,b:2               ",
    "empty list    | []                              | ''                    ",
    "empty map     | [:]                             | ''                    ",
    "bitset ranges | bits(33, 200-300, 303, 400-500) | 33,200-300,303,400-500"
  })
  void convertIterableMapAndBitSetToString(
      @ConvertWith(ConfigValueConverter.class) Object value, String rendered) {
    assertEquals(rendered, ConfigSetting.of("key", value, ConfigOrigin.DEFAULT).stringValue());
  }

  /**
   * Converts a String cell value to the most specific boxed Java primitive type. Use with
   * {@code @ConvertWith(BoxedValueConverter.class)} on {@code Object}-typed parameters when the
   * test needs actual typed values (e.g. {@code Float} not {@code String "2.33f"}).
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
  static class BoxedValueConverter implements ArgumentConverter {
    @Override
    public Object convert(Object source, ParameterContext context)
        throws ArgumentConversionException {
      if (source == null) {
        return null;
      }

      String s = source.toString();
      switch (s) {
        case "":
          return null;
        case "true":
          return Boolean.TRUE;
        case "false":
          return Boolean.FALSE;
      }
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
}
