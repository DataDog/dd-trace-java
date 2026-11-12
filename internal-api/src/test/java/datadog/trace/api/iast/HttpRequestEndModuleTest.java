package datadog.trace.api.iast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

class HttpRequestEndModuleTest {

  @TableTest({
    "scenario           | htmlValue             | expected",
    "html               | text/html             | true    ",
    "xhtml              | application/xhtml+xml | true    ",
    "other content type | somethingelse         | false   ",
    "null               |                       | false   ",
    "empty              | ''                    | false   "
  })
  void testIsHtmlResponse(String htmlValue, boolean expected) {
    HttpReqquestEndModuleTestImpl module = new HttpReqquestEndModuleTestImpl();

    boolean result = module.isHtmlResponse(htmlValue);

    assertEquals(expected, result);
  }

  @TableTest({
    "scenario            | code | expected",
    "HTTP_MOVED_PERM     | 301  | true    ",
    "HTTP_MOVED_TEMP     | 302  | true    ",
    "HTTP_NOT_MODIFIED   | 304  | true    ",
    "HTTP_NOT_FOUND      | 404  | true    ",
    "HTTP_GONE           | 410  | true    ",
    "HTTP_INTERNAL_ERROR | 500  | true    ",
    "temporary redirect  | 307  | true    ",
    "ok                  | 200  | false   ",
    "zero                | 0    | false   "
  })
  void testIsIgnorableResponseCode(int code, boolean expected) {
    HttpReqquestEndModuleTestImpl module = new HttpReqquestEndModuleTestImpl();

    boolean result = module.isIgnorableResponseCode(code);

    assertEquals(expected, result);
  }
}
