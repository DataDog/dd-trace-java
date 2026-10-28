package datadog.trace.api.normalize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.test.util.DDJavaSpecification;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class AntPatternHttpPathNormalizerTest extends DDJavaSpecification {

  @TableTest({
    "path                                       | ignoreMatchers                  | normalizedPath",
    "/com/test.jsp                              | []                              | 1             ",
    "/com/tast.jsp                              | []                              | 1             ",
    "/com/txst.jsp                              | []                              | 1             ",
    "/com/test.jsp                              | ['/com/t?st.jsp']               | 2             ",
    "/com/other.jsp                             | []                              | 2             ",
    "/com/test.jsp                              | ['/com/t?st.jsp', '/com/*.jsp'] | 3             ",
    "/com/1/test.jsp                            | []                              | 3             ",
    "/com/1/a/test.jsp                          | []                              | 3             ",
    "/com/2/test.jsp                            | []                              | 3             ",
    "/com/datadoghq/dd-trace-java/index.jsp     | []                              | 4             ",
    "/com/datadoghq/dd-trace-java/1/index.jsp   | []                              | 4             ",
    "/com/datadoghq/dd-trace-java/1/a/index.jsp | []                              | 4             ",
    "/com/datadoghq/dd-trace-java/2/index.jsp   | []                              | 4             ",
    "/com/servlet/bla.jsp                       | []                              | 5             ",
    "/com/1/servlet/bla.jsp                     | []                              | 5             ",
    "/com/1/a/servlet/bla.jsp                   | []                              | 5             ",
    "/com/2/servlet/bla.jsp                     | []                              | 5             ",
    "/org/index.html                            | []                              | 6             ",
    "/org/index.jsp                             | []                              | 6             ",
    "/test7.htm                                 | []                              | 7             ",
    "/test7.html                                | []                              | 7             ",
    "/test7.html5                               | []                              | 7             ",
    "/test8.html                                | []                              |               ",
    "/fail                                      | []                              |               "
  })
  void verifyJavadocExamplesWork(String path, List<String> ignoreMatchers, String normalizedPath) {
    // matching order matters: the first matching pattern wins
    Map<String, String> matchers = new LinkedHashMap<>();
    matchers.put("/com/t?st.jsp", "1");
    matchers.put("/com/*.jsp", "2");
    matchers.put("/com/**/test.jsp", "3");
    matchers.put("/com/datadoghq/dd-trace-java/**/*.jsp", "4");
    matchers.put("/com/**/servlet/bla.jsp", "5");
    matchers.put("/org/index.*", "6");
    matchers.put("/test7.htm*", "7");
    matchers.put("/test8.html", "8");
    matchers.keySet().removeAll(ignoreMatchers);
    AntPatternHttpPathNormalizer pathNormalizer = new AntPatternHttpPathNormalizer(matchers);

    String result = pathNormalizer.normalize(path);

    assertEquals(normalizedPath, result);
  }

  @TableTest({
    "scenario        | path          ",
    "null path       |               ",
    "relative path   | com/test.jsp  ",
    "wrong extension | /com/test.jspx",
    "directory only  | /com/datadoghq"
  })
  void verifyCounterExamplesDoNotMatch(String path) {
    Map<String, String> matchers = new LinkedHashMap<>();
    matchers.put("/com/t?st.jsp", "1");
    matchers.put("/com/*.jsp", "2");
    matchers.put("/com/**/test.jsp", "3");
    matchers.put("/com/datadoghq/dd-trace-java/**/*.jsp", "4");
    matchers.put("/com/**/servlet/bla.jsp", "5");
    AntPatternHttpPathNormalizer pathNormalizer = new AntPatternHttpPathNormalizer(matchers);

    String result = pathNormalizer.normalize(path);

    assertNull(result);
  }

  @Test
  void ignorePatternsThatAreNotPatterns() {
    // the normalizer removes invalid patterns from the given map, so it must be mutable
    Map<String, String> matchers = new HashMap<>();
    matchers.put("/not/a/pattern", "1");
    AntPatternHttpPathNormalizer normalizer = new AntPatternHttpPathNormalizer(matchers);

    String result = normalizer.normalize("/not/a/pattern");

    assertNull(result);
  }

  @TableTest({
    "path                   | normalizedPath        ",
    "/test/foo/bar.html     | /test/foo/bar.html    ",
    "/test/foo/bar/baz.html | /test/foo/bar/baz.html",
    "/dev/foo/bar/baz.html  | dev                   "
  })
  void keepTheOriginalPath(String path, String normalizedPath) {
    Map<String, String> matchers = new LinkedHashMap<>();
    matchers.put("/test/**/*.html", "*");
    matchers.put("/dev/**/*.html", "dev");
    AntPatternHttpPathNormalizer normalizer = new AntPatternHttpPathNormalizer(matchers);

    String result = normalizer.normalize(path);

    assertEquals(normalizedPath, result);
  }
}
