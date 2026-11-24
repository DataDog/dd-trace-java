package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.Test;

class ConstantPoolNeedlesTest {

  @Retention(RetentionPolicy.RUNTIME)
  @interface Marked {}

  @Retention(RetentionPolicy.RUNTIME)
  @interface Other {}

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

    @Marked
    void run() {}

    @Marked
    @Other
    Object call() {
      return null;
    }
  }

  private static final String MARKED = descriptor(Marked.class);
  private static final String OTHER = descriptor(Other.class);

  @Test
  void lowersAgentNameMatchers() {
    assertEquals(singleton("execute"), ConstantPoolNeedles.forMember(named("execute")));
    assertEquals(set("run", "call"), ConstantPoolNeedles.forMember(namedOneOf("run", "call")));
  }

  @Test
  void lowersByteBuddyNameMatchers() {
    assertEquals(
        singleton("execute"), ConstantPoolNeedles.forMember(ElementMatchers.named("execute")));
    assertNull(ConstantPoolNeedles.forMember(ElementMatchers.nameStartsWith("exec")));
  }

  @Test
  void lowersMethodSorts() {
    assertEquals(singleton("<init>"), ConstantPoolNeedles.forMember(isConstructor()));
    assertEquals(singleton("<clinit>"), ConstantPoolNeedles.forMember(isTypeInitializer()));
    assertNull(ConstantPoolNeedles.forMember(isMethod()));
  }

  @Test
  void lowersRequiredAnnotationsToDescriptors() {
    assertEquals(
        singleton(MARKED),
        ConstantPoolNeedles.forMember(isAnnotatedWith(named(Marked.class.getName()))));
    assertEquals(singleton(MARKED), ConstantPoolNeedles.forMember(isAnnotatedWith(Marked.class)));
    assertNull(ConstantPoolNeedles.forMember(isAnnotatedWith(ElementMatchers.nameStartsWith("x"))));
  }

  @Test
  void conjunctionsIntersectNamesOnly() {
    assertEquals(
        singleton("execute"),
        ConstantPoolNeedles.forMember(
            isMethod()
                .and(named("execute"))
                .and(takesArguments(1))
                .and(takesArgument(0, String.class))));
    assertEquals(
        singleton("run"),
        ConstantPoolNeedles.forMember(namedOneOf("run", "call").and(named("run"))));
    assertEquals(emptySet(), ConstantPoolNeedles.forMember(named("run").and(named("call"))));
    // a method can carry both annotations, so their descriptors must not be intersected
    assertEquals(
        1,
        ConstantPoolNeedles.forMember(
                isAnnotatedWith(Marked.class).and(isAnnotatedWith(Other.class)))
            .size());
    // a name and an annotation are different kinds of needle; either alone is a valid condition
    assertEquals(
        singleton("run"),
        ConstantPoolNeedles.forMember(named("run").and(isAnnotatedWith(Marked.class))));
  }

  @Test
  void disjunctionsNeedEveryPartLowered() {
    assertEquals(set("run", "call"), ConstantPoolNeedles.forMember(named("run").or(named("call"))));
    assertEquals(
        set("run", MARKED),
        ConstantPoolNeedles.forMember(named("run").or(isAnnotatedWith(Marked.class))));
    assertNull(ConstantPoolNeedles.forMember(named("run").or(isMethod())));
  }

  @Test
  void negationsAreNotLowered() {
    assertNull(ConstantPoolNeedles.forMember(not(named("run"))));
    assertEquals(
        singleton("run"), ConstantPoolNeedles.forMember(named("run").and(not(takesArguments(1)))));
  }

  @Test
  void lowersTypeNameMatchers() {
    assertEquals(singleton("a.B"), ConstantPoolNeedles.forTypeName(named("a.B")));
    assertEquals(set("a.B", "c.D"), ConstantPoolNeedles.forTypeName(namedOneOf("a.B", "c.D")));
    assertNull(ConstantPoolNeedles.forTypeName(ElementMatchers.isAbstract()));
  }

  @Test
  void forMembersFailsIfAnyMatcherCannotBeLowered() {
    assertEquals(
        set("run", "<init>"),
        ConstantPoolNeedles.forMembers(asList(named("run"), isConstructor())));
    assertNull(ConstantPoolNeedles.forMembers(asList(named("run"), isMethod())));
  }

  @Test
  void needlesAreInTheClassFileOfEveryMatchingType() throws IOException {
    TypeDescription fixture = TypeDescription.ForLoadedType.of(Fixture.class);
    byte[] bytecode = ConstantPoolSieveTest.bytecodeOf(Fixture.class);
    List<ElementMatcher<? super MethodDescription>> matchers = new ArrayList<>();
    matchers.add(named("execute"));
    matchers.add(named("execute").and(takesArguments(2)));
    matchers.add(namedOneOf("run", "call").and(isMethod()));
    matchers.add(named("run").or(named("call")));
    matchers.add(isConstructor());
    matchers.add(isConstructor().and(takesArguments(1)));
    matchers.add(isTypeInitializer());
    matchers.add(isAnnotatedWith(Marked.class));
    matchers.add(isAnnotatedWith(Marked.class).and(isAnnotatedWith(Other.class)));
    matchers.add(named("call").and(isAnnotatedWith(Other.class)));
    for (ElementMatcher<? super MethodDescription> matcher : matchers) {
      Set<String> needles = ConstantPoolNeedles.forMember(matcher);
      boolean anyMatch = false;
      for (MethodDescription method : fixture.getDeclaredMethods()) {
        anyMatch |= matcher.matches(method);
      }
      if (anyMatch && null != needles) {
        ConstantPoolSieve sieve = new ConstantPoolSieve(needles);
        BitSet present = new BitSet();
        assertTrue(sieve.scan(bytecode, present));
        assertFalse(present.isEmpty(), matcher + " matches but none of " + needles + " present");
      }
    }
  }

  private static String descriptor(Class<?> type) {
    return 'L' + type.getName().replace('.', '/') + ';';
  }

  private static Set<String> set(String... values) {
    return new HashSet<>(asList(values));
  }
}
