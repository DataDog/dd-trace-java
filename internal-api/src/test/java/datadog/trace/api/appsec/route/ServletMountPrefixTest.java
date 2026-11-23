package datadog.trace.api.appsec.route;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ServletMountPrefixTest {

  @TableTest({
    "Scenario                                      | Uri                           | Context      | ServletPath | PathInfo | Lookup    | Encoding     | Expected  ",
    "root mapping, cached lookup path              | '/ctx/a/b'                    | '/ctx'       | '/a/b'      |          | '/a/b'    |              | '/ctx'    ",
    "root mapping, no cached lookup path           | '/ctx/a/b'                    | '/ctx'       | '/a/b'      |          |           |              | '/ctx'    ",
    "root context, root mapping                    | '/a/b'                        | ''           | '/a/b'      |          | '/a/b'    |              | ''        ",
    "path mapping, cached lookup path              | '/ctx/api/a'                  | '/ctx'       | '/api'      | '/a'     | '/a'      |              | '/ctx/api'",
    "path mapping, no cached lookup path           | '/ctx/api/a'                  | '/ctx'       | '/api'      | '/a'     |           |              | '/ctx/api'",
    "alwaysUseFullPath includes the servlet path   | '/ctx/api/a'                  | '/ctx'       | '/api'      | '/a'     | '/api/a'  |              | '/ctx'    ",
    "jsessionid, removeSemicolonContent=true       | '/ctx/api;jsessionid=1/a'     | '/ctx'       | '/api'      | '/a'     | '/a'      |              | '/ctx/api'",
    "jsessionid, removeSemicolonContent=false      | '/ctx/api;jsessionid=1/a;m=1' | '/ctx'       | '/api'      | '/a;m=1' | '/a;m=1'  |              | '/ctx/api'",
    "uppercase jsessionid key                      | '/ctx/api;JSESSIONID=1/a;m=1' | '/ctx'       | '/api'      | '/a;m=1' | '/a;m=1'  |              | '/ctx/api'",
    "trailing jsessionid                           | '/ctx/api/a;m=1;jsessionid=1' | '/ctx'       | '/api'      | '/a;m=1' | '/a;m=1'  |              | '/ctx/api'",
    "matrix params, removeSemicolonContent=true    | '/ctx/api;v=1/a;m=1'          | '/ctx'       | '/api'      | '/a'     | '/a'      |              | '/ctx/api'",
    "matrix params, removeSemicolonContent=false   | '/ctx/api;v=1/a;m=1'          | '/ctx'       | '/api'      | '/a;m=1' | '/a;m=1'  |              | '/ctx/api'",
    "matrix param on the last segment              | '/ctx/api/a;m=1'              | '/ctx'       | '/api'      | '/a'     | '/a'      |              | '/ctx/api'",
    "double slashes collapsed                      | '/ctx//api///a'               | '/ctx'       | '/api'      | '/a'     | '/a'      |              | '/ctx/api'",
    "encoded context path                          | '/my%20ctx/a'                 | '/my%20ctx'  | '/a'        |          | '/a'      |              | '/my ctx' ",
    "encoded context path, fallback                | '/my%20ctx/a'                 | '/my%20ctx'  | '/a'        |          |           |              | '/my ctx' ",
    "plus untouched                                | '/a+b/x'                      | '/a+b'       | '/x'        |          | '/x'      |              | '/a+b'    ",
    "invalid escape kept                           | '/a%zz/x'                     | '/a%zz'      | '/x'        |          | '/x'      |              | '/a%zz'   ",
    "non-ASCII context path, UTF-8                 | '/caf%C3%A9/x'                | '/caf%C3%A9' | '/x'        |          | '/x'      | 'UTF-8'      | '/café'   ",
    "UTF-8 context path, ISO-8859-1 request        | '/caf%C3%A9/x'                | '/caf%C3%A9' | '/x'        |          | '/x'      | 'ISO-8859-1' | '/café'   ",
    "UTF-8 context path, no request encoding       | '/caf%C3%A9/x'                | '/caf%C3%A9' | '/x'        |          | '/x'      |              | '/café'   ",
    "UTF-8 context path, unsupported encoding      | '/caf%C3%A9/x'                | '/caf%C3%A9' | '/x'        |          | '/x'      | 'nope'       | '/café'   ",
    "non-ASCII servlet path, UTF-8                 | '/ctx/%C3%A9/a'               | '/ctx'       | '/é'        | '/a'     | '/a'      | 'UTF-8'      | '/ctx/é'  ",
    "non-ASCII lookup path, UTF-8                  | '/ctx/api/%C3%A9'             | '/ctx'       | '/api'      | '/é'     | '/é'      | 'UTF-8'      | '/ctx/api'",
    "non-ASCII lookup path, ISO-8859-1             | '/ctx/api/%C3%A9'             | '/ctx'       | '/api'      | '/Ã©'    | '/Ã©'     | 'ISO-8859-1' | '/ctx/api'",
    "ISO-8859-1 lookup path, non-ASCII prefix      | '/ctx/%C3%A9/%C3%A9'          | '/ctx'       | '/x'        |          | '/Ã©'     | 'ISO-8859-1' | '/ctx/é'  ",
    "lookup path starts inside an escape: fallback | '/ctx/api/%C3%A9'             | '/ctx'       | '/api'      | '/x'     | '©'       | 'ISO-8859-1' | '/ctx/api'",
    "lookup path longer than the path: fallback    | '/ctx/a'                      | '/ctx'       | '/api'      | '/x'     | '/long/a' |              | '/ctx/api'",
    "encoded slash collapsed                       | '/ctx/api/%2F/a'              | '/ctx'       | '/api'      | '/a'     | '/a'      | 'UTF-8'      | '/ctx/api'",
    "lookup path not a suffix: fallback            | '/ctx/api/a'                  | '/ctx'       | '/api'      | '/a'     | '/other'  |              | '/ctx/api'",
    "context path not a prefix: fallback           | '/other/api/a'                | '/ctx'       | '/api'      | '/a'     | '/a'      |              | '/ctx/api'",
    "null request URI: fallback                    |                               | '/ctx'       | '/api'      | '/a'     | '/a'      |              | '/ctx/api'",
    "null context path                             | '/api/a'                      |              | '/api'      | '/a'     | '/a'      |              | '/api'    ",
    "null servlet path with path info              | '/ctx/a'                      | '/ctx'       |             | '/a'     |           |              | '/ctx'    ",
    "all null                                      |                               |              |             |          |           |              | ''        ",
    "all empty                                     | ''                            | ''           | ''          |          | ''        | ''           | ''        "
  })
  void computesMountPrefix(
      String uri,
      String context,
      String servletPath,
      String pathInfo,
      String lookup,
      String encoding,
      String expected) {
    assertEquals(
        expected, ServletMountPrefix.of(uri, context, servletPath, pathInfo, lookup, encoding));
  }

  @TableTest({
    "Scenario             | Path           | Expected",
    "none                 | '/a/b'         | '/a/b'  ",
    "middle segment       | '/a;x=1/b'     | '/a/b'  ",
    "several in a segment | '/a;x=1;y=2/b' | '/a/b'  ",
    "last segment         | '/a/b;x=1'     | '/a/b'  ",
    "every segment        | '/a;x/b;y'     | '/a/b'  "
  })
  void removesSemicolonContent(String path, String expected) {
    assertEquals(expected, ServletMountPrefix.removeSemicolonContent(path));
  }

  @TableTest({
    "Scenario                | Path                    | Expected  ",
    "none                    | '/a;x=1/b'              | '/a;x=1/b'",
    "followed by a segment   | '/a;jsessionid=1/b'     | '/a/b'    ",
    "followed by a parameter | '/a;jsessionid=1;x=2/b' | '/a;x=2/b'",
    "at the end              | '/a/b;jsessionid=1'     | '/a/b'    ",
    "key without value       | '/a;jsessionid='        | '/a'      ",
    "semicolon too close     | '/a;jsess'              | '/a;jsess'"
  })
  void removesJsessionid(String path, String expected) {
    assertEquals(expected, ServletMountPrefix.removeJsessionid(path));
  }

  @TableTest({
    "Scenario       | Path     | Expected",
    "single slashes | '/a/b'   | '/a/b'  ",
    "double         | '//a//b' | '/a/b'  ",
    "triple         | '/a///b' | '/a/b'  ",
    "trailing       | '/a//'   | '/a/'   "
  })
  void collapsesSlashes(String path, String expected) {
    assertEquals(expected, ServletMountPrefix.collapseSlashes(path));
  }

  @Test
  void utf8ContextPathRendersItsOwnBytesWithoutRequestEncoding() {
    String prefix = ServletMountPrefix.of("/caf%C3%A9/x", "/caf%C3%A9", "/x", null, "/x", null);

    assertEquals("/café", prefix);
    assertEquals("/caf%C3%A9/x", SpringRouteParser.parse("/x").render(prefix, null));
  }

  @Test
  void isoEncodedLookupPathMatchesAndRendersTheRawBytes() {
    String prefix =
        ServletMountPrefix.of(
            "/ctx/%C3%A9/%C3%A9", "/ctx", "/x", null, "/\u00C3\u00A9", "ISO-8859-1");

    assertEquals("/ctx/é", prefix);
    assertEquals("/ctx/%C3%A9/%C3%A9", SpringRouteParser.parse("/é").render(prefix, null));
  }

  @TableTest({
    "Scenario                      | Raw         | Lookup | Encoding     | Expected",
    "truncated escape kept         | '/a%4/b'    | '/b'   | 'UTF-8'      | 4       ",
    "lone percent kept             | '/a%/b'     | '/b'   | 'UTF-8'      | 3       ",
    "invalid then valid escape     | '/%%41/b'   | '/b'   | 'UTF-8'      | 5       ",
    "lowercase hex                 | '/%c3%a9/b' | '/b'   | 'UTF-8'      | 7       ",
    "plus is not a space           | '/a+b/c'    | '/c'   | 'UTF-8'      | 4       ",
    "lookup path inside an escape  | '/x/%C3%A9' | '©'    | 'ISO-8859-1' | -1      ",
    "lookup path is the whole path | '/a/b'      | '/a/b' |              | 0       ",
    "not a suffix                  | '/a/b'      | '/c'   |              | -1      "
  })
  void findsTheRawEndBeforeTheLookupPath(String raw, String lookup, String encoding, int expected) {
    assertEquals(expected, ServletMountPrefix.rawEndBeforeLookupPath(raw, lookup, encoding));
  }

  @Test
  void resolvesCharsets() {
    assertSame(ISO_8859_1, ServletMountPrefix.charset(null));
    assertSame(ISO_8859_1, ServletMountPrefix.charset(""));
    assertSame(ISO_8859_1, ServletMountPrefix.charset("not a charset!"));
    assertSame(ISO_8859_1, ServletMountPrefix.charset("x-unsupported-charset"));
    assertEquals(UTF_8, ServletMountPrefix.charset("utf-8"));
  }
}
