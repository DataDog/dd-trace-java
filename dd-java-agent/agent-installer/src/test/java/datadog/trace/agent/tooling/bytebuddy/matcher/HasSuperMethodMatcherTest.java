package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperMethod;
import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.A;
import datadog.trace.api.Trace;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class HasSuperMethodMatcherTest extends AbstractHierarchyMatcherTest {

  @TableTest({
    "scenario        | type                                                                    | method | result",
    "A a             | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.A             | a      | false ",
    "B b             | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.B             | b      | true  ",
    "C c             | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.C             | c      | false ",
    "F f             | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.F             | f      | true  ",
    "G g             | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.G             | g      | false ",
    "TracedClass a   | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.TracedClass   | a      | true  ",
    "UntracedClass a | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.UntracedClass | a      | false ",
    "UntracedClass b | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.UntracedClass | b      | true  "
  })
  void testMatcher(Class<?> type, String method, boolean result) throws NoSuchMethodException {
    MethodDescription argument =
        new MethodDescription.ForLoadedMethod(type.getDeclaredMethod(method));

    assertEquals(result, hasSuperMethod(isAnnotatedWith(Trace.class)).matches(argument));
  }

  @Test
  void testConstructorNeverMatches() {
    MethodDescription method = mock(MethodDescription.class);
    ElementMatcher<MethodDescription> matcher = hasSuperMethod(none());
    when(method.isConstructor()).thenReturn(true);

    // when
    boolean result = matcher.matches(method);

    // then
    assertFalse(result);
    verify(method).isConstructor();
    verifyNoMoreInteractions(method);
  }

  @Test
  void testTraversalExceptions() throws NoSuchMethodException {
    MethodDescription method = mock(MethodDescription.class);
    ElementMatcher<MethodDescription> matcher = hasSuperMethod(none());
    MethodDescription.SignatureToken sigToken =
        new MethodDescription.ForLoadedMethod(A.class.getDeclaredMethod("a")).asSignatureToken();
    when(method.isConstructor()).thenReturn(false);
    when(method.asSignatureToken()).thenReturn(sigToken);
    when(method.getDeclaringType()).thenReturn(null);

    // when
    boolean result = matcher.matches(method);

    // then
    assertFalse(result); // default to false
    verify(method).isConstructor();
    verify(method).asSignatureToken();
    verify(method).getDeclaringType();
    verifyNoMoreInteractions(method);
  }
}
