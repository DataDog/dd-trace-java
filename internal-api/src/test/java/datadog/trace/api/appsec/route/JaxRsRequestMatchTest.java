package datadog.trace.api.appsec.route;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class JaxRsRequestMatchTest {

  @TableTest({
    "Scenario        | Raw base path         | Prefix        ",
    "null            |                       |               ",
    "empty           | ''                    | ''            ",
    "plain           | '/ctx/api/'           | '/ctx/api/'   ",
    "escapes decoded | '/my%20ctx/caf%C3%A9' | '/my ctx/café'"
  })
  void decodesTheBasePath(String rawBasePath, String prefix) {
    assertEquals(prefix, JaxRsRequestMatch.of(rawBasePath, null).prefix());
  }

  @Test
  void noBoundValuesIsNull() {
    assertNull(JaxRsRequestMatch.of("/", null).boundValues());
    assertNull(JaxRsRequestMatch.of("/", emptyMap()).boundValues());
  }

  @Test
  void listValuesKeepTheFirstElement() {
    Map<String, Object> raw = new HashMap<>();
    raw.put("path", asList("a/b", "c"));
    raw.put("empty", emptyList());
    raw.put("id", 7);
    raw.put("none", null);

    Map<String, String> bound = JaxRsRequestMatch.of("/", raw).boundValues();

    assertEquals("a/b", bound.get("path"));
    assertNull(bound.get("empty"));
    assertEquals("7", bound.get("id"));
    assertNull(bound.get("none"));
    assertEquals(4, bound.size());
  }

  @Test
  void boundValuesAreACopy() {
    List<String> values = new ArrayList<>(asList("x"));
    Map<String, Object> raw = new HashMap<>();
    raw.put("path", values);
    raw.put("id", "1");
    JaxRsRequestMatch match = JaxRsRequestMatch.of("/", raw);
    JaxRsRequestMatch single = JaxRsRequestMatch.of("/", singletonMap("path", values));

    values.set(0, "");
    raw.clear();

    assertEquals("x", match.boundValues().get("path"));
    assertEquals("1", match.boundValues().get("id"));
    assertEquals("x", single.boundValues().get("path"));
    assertThrows(UnsupportedOperationException.class, () -> match.boundValues().put("a", "b"));
    assertThrows(UnsupportedOperationException.class, () -> single.boundValues().put("a", "b"));
  }

  @TableTest({
    "Scenario             | Value | Expected           ",
    "catch-all with value | 'a/b' | '/ctx/files/{path}'",
    "catch-all only slash | '/'   | '/ctx/files/'      ",
    "catch-all empty      | ''    | '/ctx/files'       "
  })
  void presenceComesFromTheBoundValues(String value, String expected) {
    RouteTemplate template = JaxRsRouteParser.parse("/files/{path: .*}");
    JaxRsRequestMatch match =
        JaxRsRequestMatch.of("/ctx/", singletonMap("path", singletonList(value)));

    assertEquals(expected, template.render(match.prefix(), match.presenceFor(template)));
  }

  @Test
  void presenceIsUnknownWithoutBoundValuesOrOptionalParts() {
    RouteTemplate catchAll = JaxRsRouteParser.parse("/files/{path: .*}");
    RouteTemplate plain = JaxRsRouteParser.parse("/users/{id}");
    JaxRsRequestMatch match = JaxRsRequestMatch.of("/", singletonMap("id", "1"));

    assertSame(PresenceResolver.UNKNOWN, JaxRsRequestMatch.of("/", null).presenceFor(catchAll));
    assertSame(PresenceResolver.UNKNOWN, match.presenceFor(plain));
    assertSame(PresenceResolver.UNKNOWN, match.presenceFor(null));
  }
}
