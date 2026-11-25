package datadog.trace.api.appsec.route;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ServletMountPrefixTest {

  @TableTest({
    "Scenario                                       | Uri                           | Context              | ServletPath | PathMapping | Lookup       | Encoding     | Expected  ",
    "root mapping, cached lookup path               | '/ctx/a/b'                    | '/ctx'               | '/a/b'      | false       | '/a/b'       |              | '/ctx'    ",
    "root mapping, no cached lookup path            | '/ctx/a/b'                    | '/ctx'               | '/a/b'      | false       |              |              | '/ctx'    ",
    "root context, root mapping                     | '/a/b'                        | ''                   | '/a/b'      | false       | '/a/b'       |              | ''        ",
    "path mapping, cached lookup path               | '/ctx/api/a'                  | '/ctx'               | '/api'      | true        | '/a'         |              | '/ctx/api'",
    "path mapping, no cached lookup path            | '/ctx/api/a'                  | '/ctx'               | '/api'      | true        |              |              | '/ctx/api'",
    "alwaysUseFullPath includes the servlet path    | '/ctx/api/a'                  | '/ctx'               | '/api'      | true        | '/api/a'     |              | '/ctx'    ",
    "jsessionid, removeSemicolonContent=true        | '/ctx/api;jsessionid=1/a'     | '/ctx'               | '/api'      | true        | '/a'         |              | '/ctx/api'",
    "jsessionid, removeSemicolonContent=false       | '/ctx/api;jsessionid=1/a;m=1' | '/ctx'               | '/api'      | true        | '/a;m=1'     |              | '/ctx/api'",
    "uppercase jsessionid key                       | '/ctx/api;JSESSIONID=1/a;m=1' | '/ctx'               | '/api'      | true        | '/a;m=1'     |              | '/ctx/api'",
    "trailing jsessionid                            | '/ctx/api/a;m=1;jsessionid=1' | '/ctx'               | '/api'      | true        | '/a;m=1'     |              | '/ctx/api'",
    "matrix params, removeSemicolonContent=true     | '/ctx/api;v=1/a;m=1'          | '/ctx'               | '/api'      | true        | '/a'         |              | '/ctx/api'",
    "matrix params, removeSemicolonContent=false    | '/ctx/api;v=1/a;m=1'          | '/ctx'               | '/api'      | true        | '/a;m=1'     |              | '/ctx/api'",
    "matrix param on the last segment               | '/ctx/api/a;m=1'              | '/ctx'               | '/api'      | true        | '/a'         |              | '/ctx/api'",
    "double slashes collapsed                       | '/ctx//api///a'               | '/ctx'               | '/api'      | true        | '/a'         |              | '/ctx/api'",
    "encoded context path                           | '/my%20ctx/a'                 | '/my%20ctx'          | '/a'        | false       | '/a'         |              | '/my ctx' ",
    "encoded context path, fallback                 | '/my%20ctx/a'                 | '/my%20ctx'          | '/a'        | false       |              |              | '/my ctx' ",
    "plus untouched                                 | '/a+b/x'                      | '/a+b'               | '/x'        | false       | '/x'         |              | '/a+b'    ",
    "invalid escape kept                            | '/a%zz/x'                     | '/a%zz'              | '/x'        | false       | '/x'         |              | '/a%zz'   ",
    "non-ASCII context path, UTF-8                  | '/caf%C3%A9/x'                | '/caf%C3%A9'         | '/x'        | false       | '/x'         | 'UTF-8'      | '/café'   ",
    "UTF-8 context path, ISO-8859-1 request         | '/caf%C3%A9/x'                | '/caf%C3%A9'         | '/x'        | false       | '/x'         | 'ISO-8859-1' | '/café'   ",
    "UTF-8 context path, no request encoding        | '/caf%C3%A9/x'                | '/caf%C3%A9'         | '/x'        | false       | '/x'         |              | '/café'   ",
    "UTF-8 context path, unsupported encoding       | '/caf%C3%A9/x'                | '/caf%C3%A9'         | '/x'        | false       | '/x'         | 'nope'       | '/café'   ",
    "non-ASCII servlet path, UTF-8                  | '/ctx/%C3%A9/a'               | '/ctx'               | '/é'        | true        | '/a'         | 'UTF-8'      | '/ctx/é'  ",
    "non-ASCII lookup path, UTF-8                   | '/ctx/api/%C3%A9'             | '/ctx'               | '/api'      | true        | '/é'         | 'UTF-8'      | '/ctx/api'",
    "non-ASCII lookup path, ISO-8859-1              | '/ctx/api/%C3%A9'             | '/ctx'               | '/api'      | true        | '/Ã©'        | 'ISO-8859-1' | '/ctx/api'",
    "ISO-8859-1 lookup path, non-ASCII prefix       | '/ctx/%C3%A9/%C3%A9'          | '/ctx'               | '/é'        | true        | '/Ã©'        | 'ISO-8859-1' | '/ctx/é'  ",
    "lookup path starts inside an escape: fallback  | '/ctx/api/%C3%A9'             | '/ctx'               | '/api'      | true        | '©'          | 'ISO-8859-1' | '/ctx/api'",
    "lookup path longer than the path: fallback     | '/ctx/a'                      | '/ctx'               | '/api'      | true        | '/long/a'    |              | '/ctx/api'",
    "encoded slash collapsed                        | '/ctx/api/%2F/a'              | '/ctx'               | '/api'      | true        | '/a'         | 'UTF-8'      | '/ctx/api'",
    "lookup path not a suffix: fallback             | '/ctx/api/a'                  | '/ctx'               | '/api'      | true        | '/other'     |              | '/ctx/api'",
    "context path not a prefix: fallback            | '/other/api/a'                | '/ctx'               | '/api'      | true        | '/a'         |              | '/ctx/api'",
    "null request URI: fallback                     |                               | '/ctx'               | '/api'      | true        | '/a'         |              | '/ctx/api'",
    "null context path                              | '/api/a'                      |                      | '/api'      | true        | '/a'         |              | '/api'    ",
    "null servlet path with path info               | '/ctx/a'                      | '/ctx'               |             | true        |              |              | '/ctx'    ",
    "all null                                       |                               |                      |             | false       |              |              | ''        ",
    "all empty                                      | ''                            | ''                   | ''          | false       | ''           | ''           | ''        ",
    "context path parameter dropped                 | '/ctx;v=1/a'                  | '/ctx;v=1'           | '/a'        | false       | '/a'         |              | '/ctx'    ",
    "context path jsessionid dropped                | '/ctx;jsessionid=AB/a'        | '/ctx;jsessionid=AB' | '/a'        | false       | '/a'         |              | '/ctx'    ",
    "context path parameter dropped, fallback       | '/ctx;v=1/api/a'              | '/ctx;v=1'           | '/api'      | true        |              |              | '/ctx/api'",
    "parameter on each context segment              | '/a;x=1/b;y=2/c'              | '/a;x=1/b;y=2'       | '/c'        | false       | '/c'         |              | '/a/b'    ",
    "undecoded full lookup path                     | '/ctx/api/a%20b'              | '/ctx'               | '/api'      | true        | '/api/a%20b' |              | '/ctx'    ",
    "undecoded full lookup path, double slash       | '/ctx/api//a%20b'             | '/ctx'               | '/api'      | true        | '/api/a%20b' |              | '/ctx'    ",
    "undecoded lookup path within the servlet       | '/ctx/api/a%20b'              | '/ctx'               | '/api'      | true        | '/a%20b'     |              | '/ctx/api'",
    "exact mount URL of a path mapping              | '/ctx/api'                    | '/ctx'               | '/api'      | true        |              |              | '/ctx/api'",
    "exact mount URL, cached empty lookup path      | '/ctx/api'                    | '/ctx'               | '/api'      | true        | ''           |              | '/ctx/api'",
    "default mapping, no cached lookup path         | '/ctx/api'                    | '/ctx'               | '/api'      | false       |              |              | '/ctx'    ",
    "dot segments before the servlet path: fallback | '/ctx/x/../api/a'             | '/ctx'               | '/api'      | true        | '/a'         |              | '/ctx/api'",
    "dot segments, default mapping: fallback        | '/ctx/a/../b'                 | '/ctx'               | '/b'        | false       | '/b'         |              | '/ctx'    ",
    "text before the lookup path is not the servlet | '/ctx/u1/a'                   | '/ctx'               | '/a'        | false       | '/a'         |              | '/ctx'    "
  })
  void computesMountPrefix(
      String uri,
      String context,
      String servletPath,
      boolean pathMapping,
      String lookup,
      String encoding,
      String expected) {
    assertEquals(
        expected, ServletMountPrefix.of(uri, context, servletPath, pathMapping, lookup, encoding));
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
    String prefix = ServletMountPrefix.of("/caf%C3%A9/x", "/caf%C3%A9", "/x", false, "/x", null);

    assertEquals("/café", prefix);
    assertEquals("/caf%C3%A9/x", SpringRouteParser.parse("/x").render(prefix, null));
  }

  @Test
  void isoEncodedLookupPathMatchesAndRendersTheRawBytes() {
    String prefix =
        ServletMountPrefix.of(
            "/ctx/%C3%A9/%C3%A9", "/ctx", "/\u00E9", true, "/\u00C3\u00A9", "ISO-8859-1");

    assertEquals("/ctx/é", prefix);
    assertEquals("/ctx/%C3%A9/%C3%A9", SpringRouteParser.parse("/é").render(prefix, null));
  }

  @TableTest({
    "Scenario                      | Raw         | Lookup   | Encoding     | Expected",
    "truncated escape kept         | '/a%4/b'    | '/b'     | 'UTF-8'      | 4       ",
    "lone percent kept             | '/a%/b'     | '/b'     | 'UTF-8'      | 3       ",
    "invalid then valid escape     | '/%%41/b'   | '/b'     | 'UTF-8'      | 5       ",
    "lowercase hex                 | '/%c3%a9/b' | '/b'     | 'UTF-8'      | 7       ",
    "plus is not a space           | '/a+b/c'    | '/c'     | 'UTF-8'      | 4       ",
    "lookup path inside an escape  | '/x/%C3%A9' | '©'      | 'ISO-8859-1' | -1      ",
    "lookup path is the whole path | '/a/b'      | '/a/b'   |              | 0       ",
    "not a suffix                  | '/a/b'      | '/c'     |              | -1      ",
    "undecoded suffix              | '/a/b%20c'  | '/b%20c' |              | 2       ",
    "undecoded whole path, slashes | '//a%20b'   | '/a%20b' |              | 0       ",
    "undecoded, slashes, partial   | '//x/a%20b' | '/a%20b' |              | -1      "
  })
  void findsTheRawEndBeforeTheLookupPath(String raw, String lookup, String encoding, int expected) {
    assertEquals(expected, ServletMountPrefix.rawEndBeforeLookupPath(raw, lookup, encoding));
  }

  @TableTest({
    "Scenario              | Raw      | Lookup | Expected",
    "suffix                | '/api/a' | '/a'   | 4       ",
    "whole path            | '/a/b'   | '/a/b' | 0       ",
    "empty lookup path     | '/a'     | ''     | 2       ",
    "empty path            | ''       | ''     | 0       ",
    "not a suffix          | '/a/b'   | '/c'   | -1      ",
    "longer than the path  | '/a'     | '/a/b' | -1      ",
    "semicolon kept        | '/a;x/b' | '/b'   | 4       ",
    "single trailing slash | '/a/'    | '/'    | 2       "
  })
  void fastPathMatchesTheDecodingPath(String raw, String lookup, int expected) {
    assertEquals(expected, ServletMountPrefix.rawEndBeforeLookupPath(raw, lookup, null));
    assertEquals(expected, ServletMountPrefix.decodedEndBeforeLookupPath(raw, lookup, null));
  }

  @TableTest({
    "Scenario           | Context    | ServletPath | PathMapping | Expected  ",
    "path mapping       | '/ctx'     | '/api'      | true        | '/ctx/api'",
    "default mapping    | '/ctx'     | '/a/b'      | false       | '/ctx'    ",
    "parameters dropped | '/ctx;v=1' | '/api'      | true        | '/ctx/api'",
    "null context path  |            | '/api'      | true        | '/api'    ",
    "null servlet path  | '/ctx'     |             | true        | '/ctx'    "
  })
  void fallsBackToTheServletMapping(
      String context, String servletPath, boolean pathMapping, String expected) {
    assertEquals(expected, ServletMountPrefix.fallback(context, servletPath, pathMapping));
  }

  @Test
  void inferredIsNullWithoutCachedLookupPath() {
    assertNull(ServletMountPrefix.inferred("/ctx/a", "/ctx", "/a", null, null));
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
