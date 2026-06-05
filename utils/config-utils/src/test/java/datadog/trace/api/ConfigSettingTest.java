package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.junit.utils.tabletest.BoxedValueConverter;
import datadog.trace.junit.utils.tabletest.ConfigValueConverter;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

public class ConfigSettingTest {

  @TableTest({
    "scenario         | key1 | key2 | value1 | value2 | origin1  | origin2 ",
    "equal            | key  | key  | value  | value  | DEFAULT  | DEFAULT ",
    "different key    | key  | key2 | value  | value  | ENV      | ENV     ",
    "different value  | key  | key  | value2 | value  | JVM_PROP | JVM_PROP",
    "different origin | key  | key  | value  | value  | ENV      | DEFAULT "
  })
  void supportsEqualityCheck(
      String key1,
      String key2,
      Object value1,
      Object value2,
      ConfigOrigin origin1,
      ConfigOrigin origin2) {
    // when
    ConfigSetting cs1 = ConfigSetting.of(key1, value1, origin1);
    ConfigSetting cs2 = ConfigSetting.of(key2, value2, origin2);

    // then
    if (key1.equals(key2) && value1.equals(value2) && origin1 == origin2) {
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
}
