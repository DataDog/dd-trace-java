package datadog.trace.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

public class GlobPatternTest {

  @TableTest({
    "scenario       | globPattern | expectedRegex",
    "star           | '*'         | '^.*$'       ",
    "single q       | '?'         | '^.$'        ",
    "double q       | '??'        | '^..$'       ",
    "foo star       | 'Foo*'      | '^Foo.*$'    ",
    "abc literal    | 'abc'       | '^abc$'      ",
    "single q again | '?'         | '^.$'        ",
    "f q o          | 'F?o'       | '^F.o$'      ",
    "bar star       | 'Bar*'      | '^Bar.*$'    "
  })
  @ParameterizedTest(name = "Convert glob pattern to regex [{index}] {0}")
  public void convertGlobPatternToRegex(String scenario, String globPattern, String expectedRegex)
      throws Exception {
    Method method = GlobPattern.class.getDeclaredMethod("globToRegex", String.class);
    method.setAccessible(true);
    String result = (String) method.invoke(null, globPattern);
    assertEquals(expectedRegex, result);
  }
}
