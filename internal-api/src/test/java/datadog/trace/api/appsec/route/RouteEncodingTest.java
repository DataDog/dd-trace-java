package datadog.trace.api.appsec.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class RouteEncodingTest {

  @TableTest({
    "Scenario                 | Text       | Expected      ",
    "unreserved kept          | '~a-b.c_d' | '~a-b.c_d'    ",
    "two-byte UTF-8           | 'café'     | 'caf%C3%A9'   ",
    "three-byte UTF-8         | '€'        | '%E2%82%AC'   ",
    "four-byte UTF-8          | '😀'       | '%F0%9F%98%80'",
    "colon                    | 'user:me'  | 'user%3Ame'   ",
    "plus                     | 'a+b'      | 'a%2Bb'       ",
    "space                    | 'a b'      | 'a%20b'       ",
    "slash inside literal     | 'a/b'      | 'a%2Fb'       ",
    "percent literal          | '100%'     | '100%25'      ",
    "escape is always literal | '%c3%a9'   | '%25c3%25a9'  "
  })
  void encodesLiterals(String text, String expected) {
    assertEquals(expected, RouteEncoding.encodeLiteral(text));
  }

  @Test
  void unpairedSurrogateIsEncodedAsReplacementCharacter() {
    assertEquals("a%EF%BF%BDb", RouteEncoding.encodeLiteral("a\uD800b"));
    assertEquals("a%EF%BF%BD", RouteEncoding.encodeLiteral("a\uDC00"));
  }

  @TableTest({
    "Scenario                 | Raw             | Expected",
    "empty                    | ''              | ''      ",
    "no escape                | '/ctx'          | '/ctx'  ",
    "space                    | '/a%20b'        | '/a b'  ",
    "two-byte UTF-8           | '/caf%C3%A9'    | '/café' ",
    "lowercase hex            | '/caf%c3%a9'    | '/café' ",
    "four-byte UTF-8          | '/%F0%9F%98%80' | '/😀'   ",
    "plus is not a space      | '/a+b'          | '/a+b'  ",
    "encoded slash            | '/a%2Fb'        | '/a/b'  ",
    "encoded percent          | '/100%25'       | '/100%' ",
    "invalid escape kept      | '/a%zzb'        | '/a%zzb'",
    "truncated escape kept    | '/a%4'          | '/a%4'  ",
    "lone percent kept        | '/a%'           | '/a%'   ",
    "invalid then valid       | '/%%41'         | '/%A'   ",
    "malformed UTF-8 replaced | '/a%C3'         | '/a�'   "
  })
  void decodesPaths(String raw, String expected) {
    assertEquals(expected, RouteEncoding.decodePath(raw));
  }

  @Test
  void decodeReturnsTheSameInstanceWithoutEscapes() {
    String path = new String("/ctx/a+b");
    assertSame(path, RouteEncoding.decodePath(path));
    assertNull(RouteEncoding.decodePath(null));
  }
}
