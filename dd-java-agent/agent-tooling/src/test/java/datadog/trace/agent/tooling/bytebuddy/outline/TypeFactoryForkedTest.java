package datadog.trace.agent.tooling.bytebuddy.outline;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresContextField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.findType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.bytebuddy.ClassFileLocators;
import datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers;
import java.io.IOException;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;

class TypeFactoryForkedTest {
  static {
    DDElementMatchers.registerAsSupplier();
  }

  private static final ElementMatcher<TypeDescription> HAS_SUPER_TYPE =
      hasSuperType(named("java.util.concurrent.Future"));

  private static final ElementMatcher<MethodDescription> HAS_SUPER_METHOD =
      hasSuperMethod(named("tryFire"));

  private static final ElementMatcher<TypeDescription> HAS_CONTEXT_FIELD =
      declaresContextField("java.lang.Runnable", "java.lang.String");

  @Test
  void canMixFullTypesWithOutlines() throws IOException {
    // expect
    assertTrue(TypeDescription.AbstractBase.RAW_TYPES); // this test relies on raw-types

    TypeFactory typeFactory = TypeFactory.typeFactory.get();
    try {
      // when
      ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
      ClassFileLocator systemLocator = ClassFileLocators.classFileLocator(systemLoader);
      String future = "java.util.concurrent.Future";
      String forkJoinTask = "java.util.concurrent.ForkJoinTask";
      String completion = "java.util.concurrent.CompletableFuture$Completion";
      String signaller = "java.util.concurrent.CompletableFuture$Signaller";

      typeFactory.beginInstall();
      typeFactory.switchContext(systemLoader);
      typeFactory.beginTransform(completion, systemLocator.locate(completion).resolve());
      // resolve future as outline type
      findType(future).getDeclaredMethods();
      typeFactory.enableFullDescriptions();
      // resolve forkJoinTask and completion as full types
      findType(forkJoinTask).getDeclaredMethods();
      findType(completion).getDeclaredMethods();
      typeFactory.endTransform();
      typeFactory.beginTransform(signaller, systemLocator.locate(signaller).resolve());
      TypeDescription target = findType(signaller);

      // then
      // exercise going from outline (signaller) to full types (forkJoinTask, completion)
      // and back again to outline (future)
      assertFalse(HAS_CONTEXT_FIELD.matches(target));
      String superMethodName =
          target.getDeclaredMethods().stream()
              .filter(HAS_SUPER_METHOD::matches)
              .findFirst()
              .map(MethodDescription::getName)
              .orElse(null);
      assertEquals("tryFire", superMethodName);
      assertTrue(HAS_SUPER_TYPE.matches(target));
    } finally {
      // cleanup
      typeFactory.endTransform();
      typeFactory.endInstall();
    }
  }
}
