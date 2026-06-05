package datadog.trace.bootstrap.config.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.tabletest.junit.TableTest;

public class ConfigConverterTest {

  @TableTest({
    "scenario     | stringValue | expectedConvertedValue",
    "true         | true        | true                  ",
    "TRUE         | TRUE        | true                  ",
    "True         | True        | true                  ",
    "1            | 1           | true                  ",
    "false        | false       | false                 ",
    "null         |             |                       ",
    "empty string | ''          |                       ",
    "0            | 0           | false                 "
  })
  void convertBooleanProperties(String stringValue, Boolean expectedConvertedValue) {
    assertEquals(expectedConvertedValue, ConfigConverter.valueOf(stringValue, Boolean.class));
  }

  @TableTest({
    "invalidValue",
    "42.42       ",
    "tru         ",
    "truee       ",
    "'true '     ",
    "' true'     ",
    "' true '    ",
    "'   true  ' ",
    "notABool    ",
    "yes         ",
    "no          ",
    "on          ",
    "off         "
  })
  void convertBooleanPropertiesThrowsExceptionForInvalidValues(String invalidValue) {
    ConfigConverter.InvalidBooleanValueException exception =
        assertThrows(
            ConfigConverter.InvalidBooleanValueException.class,
            () -> ConfigConverter.valueOf(invalidValue, Boolean.class));
    assertTrue(exception.getMessage().contains("Invalid boolean value:"));
  }

  @TableTest({
    "mapString                                     | expected                                        ",
    "a:1, a:2, a:3                                 | [a: '3']                                        ",
    "a:b,c:d,e:                                    | [a: b, c: d]                                    ",
    "a:1  a:2  a:3                                 | [a: '3']                                        ",
    "a:b c:d e:                                    | [a: b, c: d]                                    ",
    "a:a;                                          | [a: a;]                                         ",
    "a:1, a:2, a:3                                 | [a: '3']                                        ",
    "a:1  a:2  a:3                                 | [a: '3']                                        ",
    "a:b,c:d,e:                                    | [a: b, c: d]                                    ",
    "a:b c:d e:                                    | [a: b, c: d]                                    ",
    "'key 1!:va|ue_1,'                             | [key 1!: 'va|ue_1']                             ",
    "'key 1!:va|ue_1 '                             | [key 1!: 'va|ue_1']                             ",
    "' key1 :value1 ,\t key2:  value2'             | [key1: value1, key2: value2]                    ",
    "a:b, b:c, c:d, d: e                           | [a: b, b: c, c: d, d: e]                        ",
    "key1 :value1  \t key2:  value2                | [key1: value1, key2: value2]                    ",
    "dyno:web.1 dynotype:web appname:******        | [dyno: web.1, dynotype: web, appname: ******]   ",
    "is:val:id                                     | [is: 'val:id']                                  ",
    "a:b,is:val:id,x:y                             | [a: b, is: 'val:id', x: y]                      ",
    "a:b:c:d                                       | [a: 'b:c:d']                                    ",
    "fooa:barb, foob:barc, fooc: bard, food: bare, | [fooa: barb, foob: barc, fooc: bard, food: bare]",
    "a:b=c=d                                       | [a: b=c=d]                                      ",
    "a:                                            | [:]                                             ",
    "a:b,c,d                                       | [:]                                             ",
    "a:b,c,d,k:v                                   | [:]                                             ",
    "''                                            | [:]                                             ",
    "1                                             | [:]                                             ",
    "a                                             | [:]                                             ",
    "a,1                                           | [:]                                             ",
    "!a                                            | [:]                                             ",
    "'    '                                        | [:]                                             ",
    ",,,,                                          | [:]                                             ",
    ":,:,:,:                                       | [:]                                             ",
    "': : : : '                                    | [:]                                             ",
    "::::                                          | [:]                                             ",
    "key1:val1 with_space:and_colon, key2:val2     | [:]                                             "
  })
  void parseMapProperly(String mapString, Map<String, String> expected) {
    assertEquals(expected, ConfigConverter.parseMap(mapString, "test"));
  }

  @TableTest({
    "mapString                                     | separator | expected                                        ",
    "a=1, a=2, a=3                                 | =         | [a: '3']                                        ",
    "a=b,c=d,e=                                    | =         | [a: b, c: d]                                    ",
    "a;b,c;d,e;                                    | ;         | [a: b, c: d]                                    ",
    "a=1  a=2  a=3                                 | =         | [a: '3']                                        ",
    "a=b c=d e=                                    | =         | [a: b, c: d]                                    ",
    "a=b=c=d                                       | =         | [a: b=c=d]                                      ",
    "fooa=barb, foob=barc, fooc= bard, food= bare, | =         | [fooa: barb, foob: barc, fooc: bard, food: bare]",
    "a=b:c:d                                       | =         | [a: 'b:c:d']                                    ",
    "a=                                            | =         | [:]                                             ",
    "====                                          | =         | [:]                                             "
  })
  void parseMapWithSeparator(String mapString, char separator, Map<String, String> expected) {
    assertEquals(expected, ConfigConverter.parseMap(mapString, "test", separator));
  }

  @TableTest({
    "mapString                                | separator | expected                                           ",
    "key1:value1,key2:value2                  | :         | [key1: value1, key2: value2]                       ",
    "key1:value1 key2:value2                  | :         | [key1: value1, key2: value2]                       ",
    "env:test aKey:aVal bKey:bVal cKey:       | :         | [env: test, aKey: aVal, bKey: bVal, cKey: '']      ",
    "env:test,aKey:aVal,bKey:bVal,cKey:       | :         | [env: test, aKey: aVal, bKey: bVal, cKey: '']      ",
    "env:test,aKey:aVal bKey:bVal cKey:       | :         | [env: test, aKey: 'aVal bKey:bVal cKey:']          ",
    "env:test     bKey :bVal dKey: dVal cKey: | :         | [env: test, bKey: '', dKey: '', dVal: '', cKey: '']",
    "env :test, aKey : aVal bKey:bVal cKey:   | :         | [env: test, aKey: 'aVal bKey:bVal cKey:']          ",
    "env:keyWithA:Semicolon bKey:bVal cKey    | :         | [env: 'keyWithA:Semicolon', bKey: bVal, cKey: '']  ",
    "env:keyWith:  , ,   Lots:Of:Semicolons   | :         | [env: 'keyWith:', Lots: 'Of:Semicolons']           ",
    "a:b,c,d                                  | :         | [a: b, c: '', d: '']                               ",
    "a,1                                      | :         | [a: '', 1: '']                                     ",
    "a:b:c:d                                  | :         | [a: 'b:c:d']                                       ",
    "noDelimiters                             | :         | [noDelimiters: '']                                 ",
    "'            '                           | :         | [:]                                                ",
    ",,,,,,,,,,,,                             | :         | [:]                                                ",
    "', , , , , , '                           | :         | [:]                                                "
  })
  void parsingMapWithListOfArgSeparatorsForWithKeyValueSeparator(
      String mapString, char separator, Map<String, String> expected) {
    // testing parsing for DD_TAGS
    List<Character> separatorList = Arrays.asList(',', ' ');
    assertEquals(expected, ConfigConverter.parseTraceTagsMap(mapString, separator, separatorList));
  }

  @TableTest({
    "mapString                     | expected                             | lowercaseKeys | defaultPrefix           ",
    "header1:one,header2:two       | [header1: one, header2: two]         | false         | ''                      ",
    "header1:one, header2:two      | [header1: one, header2: two]         | false         | ''                      ",
    "header1,header2:two           | [header1: header1, header2: two]     | false         | ''                      ",
    "Header1:one,header2:two       | [header1: one, header2: two]         | true          | ''                      ",
    "'\"header1:one,header2:two\"' | ['\"header1': one, header2: 'two\"'] | true          | ''                      ",
    "header1                       | [header1: header1]                   | true          | ''                      ",
    ",header1:tag                  | [header1: tag]                       | true          | ''                      ",
    "header1:tag,                  | [header1: tag]                       | true          | ''                      ",
    "header:tag:value              | [header: 'tag:value']                | true          | ''                      ",
    "''                            | [:]                                  | true          | ''                      ",
    "                              | [:]                                  | true          | ''                      ",
    "*                             | [*: 'datadog.response.headers.']     | true          | datadog.response.headers",
    "*:                            | [:]                                  | true          | datadog.response.headers",
    "*,header1:tag                 | [*: 'datadog.response.headers.']     | true          | datadog.response.headers",
    "header1:tag,*                 | [*: 'datadog.response.headers.']     | true          | datadog.response.headers",
    "1header,header2:two           | [:]                                  | true          | ''                      ",
    "header::tag                   | [:]                                  | true          | ''                      ",
    ":tag                          | [:]                                  | true          | ''                      ",
    "header:tag,:tag               | [:]                                  | true          | ''                      "
  })
  void testParseMapWithOptionalMappings(
      String mapString, Map<String, String> expected, boolean lowercaseKeys, String defaultPrefix) {
    assertEquals(
        expected,
        ConfigConverter.parseMapWithOptionalMappings(
            mapString, "test", defaultPrefix, lowercaseKeys));
  }
}
