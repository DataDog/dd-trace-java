package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.Test;

class DeclaredMethodNamesTest {

  @SuppressWarnings("unused")
  static class Fixture {
    static int counter;

    static {
      counter = 1;
    }

    Fixture() {}

    Fixture(String s) {}

    void execute(String sql) {}

    boolean execute(String sql, int flags) {
      return true;
    }

    void run() {}

    Object call() {
      return null;
    }
  }

  @Test
  void lowersAgentNameMatchers() {
    assertEquals(singleton("execute"), DeclaredMethodNames.lower(named("execute")));
    assertEquals(names("run", "call"), DeclaredMethodNames.lower(namedOneOf("run", "call")));
  }

  @Test
  void lowersByteBuddyNameMatchers() {
    assertEquals(singleton("execute"), DeclaredMethodNames.lower(ElementMatchers.named("execute")));
    assertNull(DeclaredMethodNames.lower(ElementMatchers.nameStartsWith("exec")));
  }

  @Test
  void lowersMethodSorts() {
    assertEquals(singleton("<init>"), DeclaredMethodNames.lower(isConstructor()));
    assertEquals(singleton("<clinit>"), DeclaredMethodNames.lower(isTypeInitializer()));
    assertNull(DeclaredMethodNames.lower(isMethod()));
  }

  @Test
  void conjunctionsKeepTheNamesEveryPartAllows() {
    assertEquals(
        singleton("execute"),
        DeclaredMethodNames.lower(
            isMethod()
                .and(named("execute"))
                .and(takesArguments(1))
                .and(takesArgument(0, String.class))));
    assertEquals(
        singleton("run"), DeclaredMethodNames.lower(namedOneOf("run", "call").and(named("run"))));
    assertEquals(emptySet(), DeclaredMethodNames.lower(named("run").and(named("call"))));
  }

  @Test
  void disjunctionsNeedEveryPartLowered() {
    assertEquals(names("run", "call"), DeclaredMethodNames.lower(named("run").or(named("call"))));
    assertNull(DeclaredMethodNames.lower(named("run").or(isMethod())));
  }

  @Test
  void negationsAreNotLowered() {
    assertNull(DeclaredMethodNames.lower(not(named("run"))));
    assertEquals(
        singleton("run"), DeclaredMethodNames.lower(named("run").and(not(takesArguments(1)))));
  }

  @Test
  void lowerAllFailsIfAnyMatcherCannotBeLowered() {
    assertEquals(
        names("run", "<init>"),
        DeclaredMethodNames.lowerAll(asList(named("run"), isConstructor())));
    assertNull(DeclaredMethodNames.lowerAll(asList(named("run"), isMethod())));
  }

  @Test
  void loweredNamesIncludeEveryMatchingMethod() {
    TypeDescription fixture = TypeDescription.ForLoadedType.of(Fixture.class);
    List<ElementMatcher<? super MethodDescription>> matchers = new ArrayList<>();
    matchers.add(named("execute"));
    matchers.add(named("execute").and(takesArguments(2)));
    matchers.add(namedOneOf("run", "call").and(isMethod()));
    matchers.add(named("run").or(named("call")));
    matchers.add(isConstructor());
    matchers.add(isConstructor().and(takesArguments(1)));
    matchers.add(isTypeInitializer());
    for (ElementMatcher<? super MethodDescription> matcher : matchers) {
      Set<String> lowered = DeclaredMethodNames.lower(matcher);
      for (MethodDescription method : fixture.getDeclaredMethods()) {
        if (matcher.matches(method)) {
          assertTrue(
              null == lowered || lowered.contains(method.getInternalName()),
              matcher + " matches " + method + " but lowered to " + lowered);
        }
      }
    }
  }

  private static Set<String> names(String... names) {
    return new HashSet<>(asList(names));
  }
}
