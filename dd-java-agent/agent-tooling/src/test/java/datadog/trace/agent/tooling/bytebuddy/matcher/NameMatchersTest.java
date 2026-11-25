package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedNoneOf;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.notExcludedByName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType;
import datadog.trace.test.util.DDJavaSpecification;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class NameMatchersTest extends DDJavaSpecification {

  @TableTest({
    "scenario      | name    | expected",
    "first name    | foo     | true    ",
    "second name   | bar     | true    ",
    "unlisted name | missing | false   "
  })
  void testNamedOneOf(String name, boolean expected) {
    // setup
    NamedElement named = namedElement(name);
    ElementMatcher<NamedElement> matcher = namedOneOf("foo", "bar");

    // when
    boolean result = matcher.matches(named);

    // then
    assertEquals(expected, result);
  }

  @TableTest({
    "scenario      | name    | expected",
    "first name    | foo     | false   ",
    "second name   | bar     | false   ",
    "unlisted name | missing | true    "
  })
  void testNamedNoneOf(String name, boolean expected) {
    // setup
    NamedElement named = namedElement(name);
    ElementMatcher<NamedElement> matcher = namedNoneOf("foo", "bar");

    // when
    boolean result = matcher.matches(named);

    // then
    assertEquals(expected, result);
  }

  @TableTest({
    "scenario       | name | expected",
    "same name      | foo  | true    ",
    "different name | bar  | false   "
  })
  void testNamed(String name, boolean expected) {
    // setup
    NamedElement named = namedElement(name);
    ElementMatcher<NamedElement> matcher = named("foo");

    // when
    boolean result = matcher.matches(named);

    // then
    assertEquals(expected, result);
  }

  @TableTest({
    "scenario         | name    | expected",
    "exact prefix     | foo     | true    ",
    "longer name      | food    | true    ",
    "prefix in middle | barfood | false   "
  })
  void testNameStartsWith(String name, boolean expected) {
    // setup
    NamedElement named = namedElement(name);
    ElementMatcher<NamedElement> matcher = nameStartsWith("foo");

    // when
    boolean result = matcher.matches(named);

    // then
    assertEquals(expected, result);
  }

  @TableTest({
    "scenario         | name  | expected",
    "matching suffix  | tofoo | true    ",
    "different suffix | tofu  | false   "
  })
  void testNameEndsWith(String name, boolean expected) {
    // setup
    NamedElement named = namedElement(name);
    ElementMatcher<NamedElement> matcher = nameEndsWith("foo");

    // when
    boolean result = matcher.matches(named);

    // then
    assertEquals(expected, result);
  }

  @Test
  void testNotExcludedByName() {
    NamedElement named = namedElement("java.util.concurrent.ConcurrentHashMap$Foo");

    // when
    ElementMatcher<NamedElement> fjtExcluder = notExcludedByName(ExcludeType.FORK_JOIN_TASK);
    // then
    assertFalse(fjtExcluder.matches(named));

    // when
    ElementMatcher<NamedElement> executorExcluder = notExcludedByName(ExcludeType.EXECUTOR);
    // then
    assertTrue(executorExcluder.matches(named));
  }

  private static NamedElement namedElement(String name) {
    NamedElement named = mock(NamedElement.class);
    when(named.getActualName()).thenReturn(name);
    return named;
  }
}
