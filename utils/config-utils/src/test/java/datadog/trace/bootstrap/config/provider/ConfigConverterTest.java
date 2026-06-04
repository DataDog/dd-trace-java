package datadog.trace.bootstrap.config.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.test.util.DDJavaSpecification;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

public class ConfigConverterTest extends DDJavaSpecification {

  @ParameterizedTest
  @MethodSource("convertBooleanPropertiesArguments")
  void convertBooleanProperties(String stringValue, Boolean expectedConvertedValue) {
    assertEquals(expectedConvertedValue, ConfigConverter.valueOf(stringValue, Boolean.class));
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> convertBooleanPropertiesArguments() {
    return Stream.of(
        arguments("true", true),
        arguments("TRUE", true),
        arguments("True", true),
        arguments("1", true),
        arguments("false", false),
        arguments((String) null, (Boolean) null),
        arguments("", (Boolean) null),
        arguments("0", false));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "42.42",
        "tru",
        "truee",
        "true ",
        " true",
        " true ",
        "   true  ",
        "notABool",
        "yes",
        "no",
        "on",
        "off"
      })
  void convertBooleanPropertiesThrowsExceptionForInvalidValues(String invalidValue) {
    ConfigConverter.InvalidBooleanValueException exception =
        assertThrows(
            ConfigConverter.InvalidBooleanValueException.class,
            () -> ConfigConverter.valueOf(invalidValue, Boolean.class));
    assertEquals(true, exception.getMessage().contains("Invalid boolean value:"));
  }

  @ParameterizedTest
  @MethodSource("parseMapProperlyArguments")
  void parseMapProperly(String mapString, Map<String, String> expected) {
    // spotless:off
    assertEquals(expected, ConfigConverter.parseMap(mapString, "test"));
    // spotless:on
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> parseMapProperlyArguments() {
    return Stream.of(
        // spotless:off
        arguments("a:1, a:2, a:3",                                 mapOf("a", "3")),
        arguments("a:b,c:d,e:",                                    mapOf("a", "b", "c", "d")),
        // space separated
        arguments("a:1  a:2  a:3",                                 mapOf("a", "3")),
        arguments("a:b c:d e:",                                    mapOf("a", "b", "c", "d")),
        // More different string variants:
        arguments("a:a;",                                          mapOf("a", "a;")),
        arguments("a:1, a:2, a:3",                                 mapOf("a", "3")),
        arguments("a:1  a:2  a:3",                                 mapOf("a", "3")),
        arguments("a:b,c:d,e:",                                    mapOf("a", "b", "c", "d")),
        arguments("a:b c:d e:",                                    mapOf("a", "b", "c", "d")),
        arguments("key 1!:va|ue_1,",                               mapOf("key 1!", "va|ue_1")),
        arguments("key 1!:va|ue_1 ",                               mapOf("key 1!", "va|ue_1")),
        arguments(" key1 :value1 ,\t key2:  value2",               mapOf("key1", "value1", "key2", "value2")),
        arguments("a:b, b:c, c:d, d: e",                          mapOf("a", "b", "b", "c", "c", "d", "d", "e")),
        arguments("key1 :value1  \t key2:  value2",                mapOf("key1", "value1", "key2", "value2")),
        arguments("dyno:web.1 dynotype:web appname:******",        mapOf("dyno", "web.1", "dynotype", "web", "appname", "******")),
        arguments("is:val:id",                                     mapOf("is", "val:id")),
        arguments("a:b,is:val:id,x:y",                            mapOf("a", "b", "is", "val:id", "x", "y")),
        arguments("a:b:c:d",                                       mapOf("a", "b:c:d")),
        arguments("fooa:barb, foob:barc, fooc: bard, food: bare,", mapOf("fooa", "barb", "foob", "barc", "fooc", "bard", "food", "bare")),
        arguments("a:b=c=d",                                       mapOf("a", "b=c=d")),
        // Illegal
        arguments("a:",                                            Collections.<String, String>emptyMap()),
        arguments("a:b,c,d",                                       Collections.<String, String>emptyMap()),
        arguments("a:b,c,d,k:v",                                   Collections.<String, String>emptyMap()),
        arguments("",                                              Collections.<String, String>emptyMap()),
        arguments("1",                                             Collections.<String, String>emptyMap()),
        arguments("a",                                             Collections.<String, String>emptyMap()),
        arguments("a,1",                                           Collections.<String, String>emptyMap()),
        arguments("!a",                                            Collections.<String, String>emptyMap()),
        arguments("    ",                                          Collections.<String, String>emptyMap()),
        arguments(",,,,",                                          Collections.<String, String>emptyMap()),
        arguments(":,:,:,:",                                       Collections.<String, String>emptyMap()),
        arguments(": : : : ",                                      Collections.<String, String>emptyMap()),
        arguments("::::",                                          Collections.<String, String>emptyMap()),
        arguments("key1:val1 with_space:and_colon, key2:val2",     Collections.<String, String>emptyMap())
        // spotless:on
        );
  }

  @ParameterizedTest
  @MethodSource("parseMapWithSeparatorArguments")
  void parseMapWithSeparator(String mapString, char separator, Map<String, String> expected) {
    // spotless:off
    assertEquals(expected, ConfigConverter.parseMap(mapString, "test", separator));
    // spotless:on
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> parseMapWithSeparatorArguments() {
    return Stream.of(
        // spotless:off
        arguments("a=1, a=2, a=3",                                 '=', mapOf("a", "3")),
        arguments("a=b,c=d,e=",                                    '=', mapOf("a", "b", "c", "d")),
        arguments("a;b,c;d,e;",                                    ';', mapOf("a", "b", "c", "d")),
        // space separated
        arguments("a=1  a=2  a=3",                                 '=', mapOf("a", "3")),
        arguments("a=b c=d e=",                                    '=', mapOf("a", "b", "c", "d")),
        // More different string variants
        arguments("a=b=c=d",                                       '=', mapOf("a", "b=c=d")),
        arguments("fooa=barb, foob=barc, fooc= bard, food= bare,", '=', mapOf("fooa", "barb", "foob", "barc", "fooc", "bard", "food", "bare")),
        arguments("a=b:c:d",                                       '=', mapOf("a", "b:c:d")),
        // Illegal
        arguments("a=",                                            '=', Collections.<String, String>emptyMap()),
        arguments("====",                                          '=', Collections.<String, String>emptyMap())
        // spotless:on
        );
  }

  @ParameterizedTest
  @MethodSource("parseTraceTagsMapArguments")
  void parsingMapWithListOfArgSeparatorsForWithKeyValueSeparator(
      String mapString, char separator, Map<String, String> expected) {
    // testing parsing for DD_TAGS
    List<Character> separatorList = Arrays.asList(',', ' ');
    // spotless:off
    assertEquals(expected, ConfigConverter.parseTraceTagsMap(mapString, separator, separatorList));
    // spotless:on
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> parseTraceTagsMapArguments() {
    return Stream.of(
        // spotless:off
        arguments("key1:value1,key2:value2",                       ':', mapOf("key1", "value1", "key2", "value2")),
        arguments("key1:value1 key2:value2",                       ':', mapOf("key1", "value1", "key2", "value2")),
        arguments("env:test aKey:aVal bKey:bVal cKey:",            ':', mapOf("env", "test", "aKey", "aVal", "bKey", "bVal", "cKey", "")),
        arguments("env:test,aKey:aVal,bKey:bVal,cKey:",            ':', mapOf("env", "test", "aKey", "aVal", "bKey", "bVal", "cKey", "")),
        arguments("env:test,aKey:aVal bKey:bVal cKey:",            ':', mapOf("env", "test", "aKey", "aVal bKey:bVal cKey:")),
        arguments("env:test     bKey :bVal dKey: dVal cKey:",      ':', mapOf("env", "test", "bKey", "", "dKey", "", "dVal", "", "cKey", "")),
        arguments("env :test, aKey : aVal bKey:bVal cKey:",        ':', mapOf("env", "test", "aKey", "aVal bKey:bVal cKey:")),
        arguments("env:keyWithA:Semicolon bKey:bVal cKey",         ':', mapOf("env", "keyWithA:Semicolon", "bKey", "bVal", "cKey", "")),
        arguments("env:keyWith:  , ,   Lots:Of:Semicolons ",       ':', mapOf("env", "keyWith:", "Lots", "Of:Semicolons")),
        arguments("a:b,c,d",                                       ':', mapOf("a", "b", "c", "", "d", "")),
        arguments("a,1",                                           ':', mapOf("a", "", "1", "")),
        arguments("a:b:c:d",                                       ':', mapOf("a", "b:c:d")),
        // edge cases
        arguments("noDelimiters",                                  ':', mapOf("noDelimiters", "")),
        arguments("            ",                                  ':', Collections.<String, String>emptyMap()),
        arguments(",,,,,,,,,,,,",                                  ':', Collections.<String, String>emptyMap()),
        arguments(", , , , , , ",                                  ':', Collections.<String, String>emptyMap())
        // spotless:on
        );
  }

  @ParameterizedTest
  @MethodSource("parseMapWithOptionalMappingsArguments")
  void testParseMapWithOptionalMappings(
      String mapString, Map<String, String> expected, boolean lowercaseKeys, String defaultPrefix) {
    assertEquals(
        expected,
        ConfigConverter.parseMapWithOptionalMappings(
            mapString, "test", defaultPrefix, lowercaseKeys));
  }

  static Stream<org.junit.jupiter.params.provider.Arguments>
      parseMapWithOptionalMappingsArguments() {
    return Stream.of(
        arguments("header1:one,header2:two", mapOf("header1", "one", "header2", "two"), false, ""),
        arguments("header1:one, header2:two", mapOf("header1", "one", "header2", "two"), false, ""),
        arguments("header1,header2:two", mapOf("header1", "header1", "header2", "two"), false, ""),
        arguments("Header1:one,header2:two", mapOf("header1", "one", "header2", "two"), true, ""),
        arguments(
            "\"header1:one,header2:two\"", mapOf("\"header1", "one", "header2", "two\""), true, ""),
        arguments("header1", mapOf("header1", "header1"), true, ""),
        arguments(",header1:tag", mapOf("header1", "tag"), true, ""),
        arguments("header1:tag,", mapOf("header1", "tag"), true, ""),
        arguments("header:tag:value", mapOf("header", "tag:value"), true, ""),
        arguments("", Collections.<String, String>emptyMap(), true, ""),
        arguments((String) null, Collections.<String, String>emptyMap(), true, ""),
        // Test for wildcard header tags
        arguments("*", mapOf("*", "datadog.response.headers."), true, "datadog.response.headers"),
        arguments("*:", Collections.<String, String>emptyMap(), true, "datadog.response.headers"),
        arguments(
            "*,header1:tag",
            mapOf("*", "datadog.response.headers."),
            true,
            "datadog.response.headers"),
        arguments(
            "header1:tag,*",
            mapOf("*", "datadog.response.headers."),
            true,
            "datadog.response.headers"),
        // logs warning: Illegal key only tag starting with non letter '1header'
        arguments("1header,header2:two", Collections.<String, String>emptyMap(), true, ""),
        // logs warning: Illegal tag starting with non letter for key 'header'
        arguments("header::tag", Collections.<String, String>emptyMap(), true, ""),
        // logs warning: Illegal empty key at position 0
        arguments(":tag", Collections.<String, String>emptyMap(), true, ""),
        // logs warning: Illegal empty key at position 11
        arguments("header:tag,:tag", Collections.<String, String>emptyMap(), true, ""));
  }

  private static Map<String, String> mapOf(String... keysAndValues) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put(keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
  }
}
