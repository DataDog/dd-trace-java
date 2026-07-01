package datadog.trace.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

class GlobPatternTest {

  @TableTest({
    "scenario             | globPattern | expectedRegex",
    "star alone           | '*'         | '^.*$'       ",
    "question alone       | '?'         | '^.$'        ",
    "two questions        | '??'        | '^..$'       ",
    "prefix star          | 'Foo*'      | '^Foo.*$'    ",
    "literal abc          | 'abc'       | '^abc$'      ",
    "question alone (dup) | '?'         | '^.$'        ",
    "single char          | 'F?o'       | '^F.o$'      ",
    "Bar prefix           | 'Bar*'      | '^Bar.*$'    "
  })
  void convertGlobPatternToRegex(String globPattern, String expectedRegex) {
    assertEquals(expectedRegex, GlobPattern.globToRegex(globPattern));
  }
}
