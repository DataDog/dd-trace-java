package datadog.trace.api.sampling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

class SamplingRuleTest {

  @TableTest({
    "scenario       | glob  | normalized",
    // '*' is SamplingRule.MATCH_ALL
    "null           |       | '*'       ",
    "match all      | '*'   | '*'       ",
    "double star    | '**'  | '**'      ",
    "literal        | a     | a         ",
    "trailing star  | 'a*'  | 'a*'      ",
    "trailing stars | 'a**' | 'a**'     ",
    "inner star     | 'a*b' | 'a*b'     ",
    "empty          | ''    | ''        "
  })
  void testNormalizeGlob(String glob, String normalized) {
    assertEquals(normalized, SamplingRule.normalizeGlob(glob));
  }
}
