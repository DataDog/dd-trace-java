package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ExtendsClassMatcherTest extends AbstractHierarchyMatcherTest {

  @TableTest({
    "scenario | matcherClass                                                | type                                                        | result",
    "A -> B   | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.A | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.B | false ",
    "A -> F   | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.A | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.F | false ",
    "G -> F   | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.G | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.F | false ",
    "F -> F   | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.F | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.F | true  ",
    "F -> G   | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.F | datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.G | true  "
  })
  void testMatcher(Class<?> matcherClass, Class<?> type, boolean result) {
    assertEquals(result, extendsClass(named(matcherClass.getName())).matches(describe(type)));
  }

  @Test
  void testTraversalExceptions() {
    TypeDescription type = mock(TypeDescription.class);
    TypeDescription.Generic typeGeneric = mock(TypeDescription.Generic.class);
    ElementMatcher<TypeDescription> matcher = extendsClass(named(Object.class.getName()));
    when(type.isInterface()).thenReturn(false);
    when(type.asGenericType()).thenReturn(typeGeneric);
    when(type.getTypeName()).thenReturn("type-name");
    when(typeGeneric.asErasure()).thenAnswer(throwing("asErasure exception"));
    when(typeGeneric.getTypeName()).thenReturn("typeGeneric-name");
    when(type.getSuperClass()).thenAnswer(throwing("getSuperClass exception"));

    // when
    boolean result = matcher.matches(type);

    // then
    assertFalse(result); // default to false
    verify(type).isInterface();
    verify(type).asGenericType();
    verify(type).getTypeName();
    verify(typeGeneric).asErasure();
    verify(typeGeneric).getTypeName();
    verify(type).getSuperClass();
    verifyNoMoreInteractions(type, typeGeneric);
  }
}
