package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

public class ConfigSettingTest {

  @ParameterizedTest
  @MethodSource("supportsEqualityCheckArguments")
  void supportsEqualityCheck(
      String key1,
      String key2,
      Object value1,
      Object value2,
      ConfigOrigin origin1,
      ConfigOrigin origin2,
      boolean expectedEqual) {
    // when
    ConfigSetting cs1 = ConfigSetting.of(key1, value1, origin1);
    ConfigSetting cs2 = ConfigSetting.of(key2, value2, origin2);

    // then
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

  static Stream<org.junit.jupiter.params.provider.Arguments> supportsEqualityCheckArguments() {
    return Stream.of(
        arguments("key", "key", "value", "value", ConfigOrigin.DEFAULT, ConfigOrigin.DEFAULT, true),
        arguments("key", "key2", "value", "value", ConfigOrigin.ENV, ConfigOrigin.ENV, false),
        arguments(
            "key", "key", "value2", "value", ConfigOrigin.JVM_PROP, ConfigOrigin.JVM_PROP, false),
        arguments("key", "key", "value", "value", ConfigOrigin.ENV, ConfigOrigin.DEFAULT, false));
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

  @ParameterizedTest
  @MethodSource("supportBasicTypesArguments")
  void supportBasicTypes(Object value, String rendered) {
    assertEquals(rendered, ConfigSetting.of("key", value, ConfigOrigin.DEFAULT).stringValue());
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> supportBasicTypesArguments() {
    return Stream.of(
        arguments((Object) null, (String) null),
        arguments(true, "true"),
        arguments(false, "false"),
        arguments(1, "1"),
        arguments(1.0d, "1.0"),
        arguments(2.33f, "2.33"),
        arguments("string", "string"));
  }

  @ParameterizedTest
  @MethodSource("convertIterableMapAndBitSetArguments")
  void convertIterableMapAndBitSetToString(Object value, String rendered) {
    assertEquals(rendered, ConfigSetting.of("key", value, ConfigOrigin.DEFAULT).stringValue());
  }

  static Stream<org.junit.jupiter.params.provider.Arguments>
      convertIterableMapAndBitSetArguments() {
    Map<String, Integer> mapStringInt = new LinkedHashMap<>();
    mapStringInt.put("a", 1);
    mapStringInt.put("b", 2);

    Map<String, String> mapStringString = new LinkedHashMap<>();
    mapStringString.put("a", "1");
    mapStringString.put("b", "2");

    return Stream.of(
        arguments(Arrays.asList("1", "2", "3"), "1,2,3"),
        arguments(Arrays.asList(1, 2, 3), "1,2,3"),
        arguments(Arrays.asList(1.0f, 22.23d, 3.1415d), "1.0,22.23,3.1415"),
        arguments(mapStringInt, "a:1,b:2"),
        arguments(mapStringString, "a:1,b:2"),
        arguments(new LinkedHashMap<>(), ""),
        arguments(Arrays.<String>asList(), ""),
        arguments(bitSetIntervals(), "33,200-300,303,400-500"));
  }

  private static BitSet bitSetIntervals() {
    BitSet bitSet = new BitSet();
    bitSet.set(33);
    bitSet.set(200, 300);
    bitSet.set(303);
    bitSet.set(400, 500);
    return bitSet;
  }
}
