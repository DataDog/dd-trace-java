package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeList;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ImplementsInterfaceMatcherTest extends AbstractHierarchyMatcherTest {

  @TableTest({
    "scenario | matcherClass                                                | type                                                        | result",
    "A -> A   | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.A | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.A | false ",
    "A -> B   | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.A | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.B | false ",
    "B -> A   | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.B | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.A | false ",
    "A -> E   | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.A | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.E | false ",
    "A -> F   | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.A | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.F | true  ",
    "A -> G   | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.A | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.G | true  ",
    "F -> A   | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.F | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.A | false ",
    "F -> F   | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.F | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.F | false ",
    "F -> G   | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.F | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.G | false "
  })
  void testMatcher(Class<?> matcherClass, Class<?> type, boolean result) {
    assertEquals(
        result, implementsInterface(named(matcherClass.getName())).matches(describe(type)));
  }

  @Test
  void testExceptionGettingInterfaces() {
    TypeDescription type = mock(TypeDescription.class);
    ElementMatcher<TypeDescription> matcher = implementsInterface(named(Object.class.getName()));
    when(type.isInterface()).thenReturn(false);
    when(type.getInterfaces()).thenAnswer(throwing("getInterfaces exception"));
    when(type.getSuperClass()).thenAnswer(throwing("getSuperClass exception"));
    when(type.getTypeName()).thenReturn("type-name");

    // when
    boolean result = matcher.matches(type);

    // then
    assertFalse(result); // default to false
    verify(type).isInterface();
    verify(type).getInterfaces();
    verify(type).getSuperClass();
    verify(type, times(2)).getTypeName();
    verifyNoMoreInteractions(type);
  }

  @Test
  void testTraversalExceptions() {
    TypeDescription type = mock(TypeDescription.class);
    ElementMatcher<TypeDescription> matcher = implementsInterface(named(Object.class.getName()));
    TypeList.Generic interfaces = mock(TypeList.Generic.class);
    when(type.isInterface()).thenReturn(false);
    when(type.getInterfaces()).thenReturn(interfaces);
    when(interfaces.iterator()).thenReturn(new ThrowOnFirstElement<>());
    when(type.getTypeName()).thenReturn("type-name");
    when(type.getSuperClass()).thenAnswer(throwing("getSuperClass exception"));

    // when
    boolean result = matcher.matches(type);

    // then
    assertFalse(result); // default to false
    verify(type).isInterface();
    verify(type).getInterfaces();
    verify(interfaces).iterator();
    verify(type, times(2)).getTypeName();
    verify(type).getSuperClass();
    verifyNoMoreInteractions(type, interfaces);
  }
}
