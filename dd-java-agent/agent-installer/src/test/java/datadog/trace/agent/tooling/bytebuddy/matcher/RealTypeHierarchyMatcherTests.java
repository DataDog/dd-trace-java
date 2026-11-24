package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.FutureTask;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.tabletest.junit.TableTest;

class RealTypeHierarchyMatcherTests extends AbstractHierarchyMatcherTest {

  @TableTest({
    "scenario                                                  | clazz                                            | implementsRunnable",
    "direct match                                              | datadog.trace.agent.test.DirectRunnable          | true              ",
    "transitive match via interface                            | java.util.concurrent.FutureTask                  | true              ",
    "transitive match via class then interface                 | datadog.trace.agent.test.NoInterfacesInTheMiddle | true              ",
    "class extends Object has no match                         | java.util.concurrent.ForkJoinTask                | false             ",
    "no match with class inheritance                           | java.lang.StringBuffer                           | false             ",
    "interface doesn't match                                   | java.util.concurrent.Callable                    | false             ",
    "reject interface targets, even if they have the interface | datadog.trace.agent.test.ExtendedRunnable        | false             ",
    "an interface can't implement itself                       | java.lang.Runnable                               | false             "
  })
  void testImplementsInterface(Class<?> clazz, boolean implementsRunnable) {
    ElementMatcher<TypeDescription> matcher = implementsInterface(named(Runnable.class.getName()));

    assertEquals(implementsRunnable, matcher.matches(new TypeDescription.ForLoadedType(clazz)));
  }

  @TableTest({
    "scenario                                  | clazz                                            | implementsRunnable",
    "direct match                              | datadog.trace.agent.test.DirectRunnable          | true              ",
    "transitive match via interface            | java.util.concurrent.FutureTask                  | true              ",
    "transitive match via class then interface | datadog.trace.agent.test.NoInterfacesInTheMiddle | true              ",
    "class extends Object has no match         | java.util.concurrent.ForkJoinTask                | false             ",
    "no match with class inheritance           | java.lang.StringBuffer                           | false             ",
    "interface doesn't match                   | java.util.concurrent.Callable                    | false             ",
    "accept interface targets                  | datadog.trace.agent.test.ExtendedRunnable        | true              ",
    "accept the interface itself               | java.lang.Runnable                               | true              "
  })
  void testHasInterface(Class<?> clazz, boolean implementsRunnable) {
    ElementMatcher<TypeDescription> matcher = hasInterface(named(Runnable.class.getName()));

    assertEquals(implementsRunnable, matcher.matches(new TypeDescription.ForLoadedType(clazz)));
  }

  @TableTest({
    "scenario                                  | clazz                                            | implementsRunnable",
    "direct match                              | datadog.trace.agent.test.DirectRunnable          | true              ",
    "transitive match via interface            | java.util.concurrent.FutureTask                  | true              ",
    "transitive match via class then interface | datadog.trace.agent.test.NoInterfacesInTheMiddle | true              ",
    "class extends Object has no match         | java.util.concurrent.ForkJoinTask                | false             ",
    "no match with class inheritance           | java.lang.StringBuffer                           | false             ",
    "interface doesn't match                   | java.util.concurrent.Callable                    | false             ",
    "reject interface targets                  | datadog.trace.agent.test.ExtendedRunnable        | false             ",
    "reject the interface itself               | java.lang.Runnable                               | false             "
  })
  void testHasSuperType(Class<?> clazz, boolean implementsRunnable) {
    ElementMatcher<TypeDescription> matcher = hasSuperType(named(Runnable.class.getName()));

    assertEquals(implementsRunnable, matcher.matches(new TypeDescription.ForLoadedType(clazz)));
  }

  @TableTest({
    "scenario                          | clazz                                            | extendsFutureTask",
    "match the class itself            | java.util.concurrent.FutureTask                  | true             ",
    "direct supertype relationship     | datadog.trace.agent.test.NoInterfacesInTheMiddle | true             ",
    "transitive supertype relationship | datadog.trace.agent.test.LeafFutureTask          | true             ",
    "class extends Object has no match | java.util.concurrent.ForkJoinTask                | false            ",
    "no match with class inheritance   | java.lang.StringBuffer                           | false            ",
    "reject interface targets          | datadog.trace.agent.test.ExtendedRunnable        | false            "
  })
  void testExtendsClass(Class<?> clazz, boolean extendsFutureTask) {
    ElementMatcher<TypeDescription> matcher = extendsClass(named(FutureTask.class.getName()));

    assertEquals(extendsFutureTask, matcher.matches(new TypeDescription.ForLoadedType(clazz)));
  }
}
