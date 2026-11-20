package datadog.trace.agent.tooling.muzzle;

import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_INTERFACE;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_NON_INTERFACE;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_NON_STATIC;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_PUBLIC_OR_PROTECTED;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_STATIC;
import static datadog.trace.agent.tooling.muzzle.ReferenceCreatorTestSupport.referencesFrom;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.utils.ClasspathUtils;
import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.agent.tooling.muzzle.Reference.Mismatch.MissingClass;
import datadog.trace.agent.tooling.muzzle.TestAdviceClasses.MethodBodyAdvice;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverter;

public class ReferenceMatcherTest extends DDJavaSpecification {
  static {
    SharedTypePools.registerIfAbsent(SharedTypePools.simpleCache());
    HierarchyMatchers.registerIfAbsent(HierarchyMatchers.simpleChecks());
  }

  private static ClassLoader safeClasspath;
  private static ClassLoader unsafeClasspath;
  private static ClassLoader testClasspath;
  private static TypePool testTypePool;

  @BeforeAll
  static void setupSpec() throws IOException {
    safeClasspath =
        new URLClassLoader(
            new URL[] {
              ClasspathUtils.createJarWithClasses(
                  MethodBodyAdvice.A.class,
                  MethodBodyAdvice.B.class,
                  MethodBodyAdvice.SomeInterface.class,
                  // pattern in e.g. AWS SDK where empty interfaces join other interfaces
                  MethodBodyAdvice.SkipLevel.class,
                  MethodBodyAdvice.HasMethod.class,
                  MethodBodyAdvice.SomeImplementation.class)
            },
            null);
    unsafeClasspath =
        new URLClassLoader(
            new URL[] {
              ClasspathUtils.createJarWithClasses(
                  MethodBodyAdvice.A.class,
                  MethodBodyAdvice.SomeInterface.class,
                  MethodBodyAdvice.SomeImplementation.class)
            },
            null);
    testClasspath = ReferenceMatcherTest.class.getClassLoader();
    testTypePool = SharedTypePools.typePool(testClasspath);
  }

  @Test
  void matchSafeClasspaths() {
    Reference[] refs = referencesFrom(MethodBodyAdvice.class).values().toArray(new Reference[0]);
    ReferenceMatcher refMatcher = new ReferenceMatcher(refs);

    assertEquals(
        emptySet(), getMismatchClassSet(refMatcher.getMismatchedReferenceSources(safeClasspath)));
    assertEquals(
        singleton(MissingClass.class),
        getMismatchClassSet(refMatcher.getMismatchedReferenceSources(unsafeClasspath)));
  }

  @Test
  @DisabledIfSystemProperty(
      named = "java.runtime.name",
      matches = ".*Semeru.*",
      disabledReason = "Often fails in Semeru runtime")
  void matchingDoesNotHoldAStrongReferenceToClassloaders() throws InterruptedException {
    assertTrue(MuzzleWeakReferenceTest.classLoaderRefIsGarbageCollected());
  }

  @TableTest({
    "scenario                                   | referenceFlags          | classToCheck | expectedMismatches",
    "B expecting non interface matches B        | [EXPECTS_NON_INTERFACE] | B            | []                ",
    "B expecting interface produces MissingFlag | [EXPECTS_INTERFACE]     | B            | [MissingFlag]     "
  })
  void matchingRef(
      List<Integer> referenceFlags, Class<?> classToCheck, List<Class<?>> expectedMismatches) {
    Reference ref =
        new Reference.Builder(classToCheck.getName()).withFlag(flags(referenceFlags)).build();
    List<Reference.Mismatch> mismatches = new ArrayList<>();

    // when
    ReferenceMatcher.checkReference(testTypePool, ref, testClasspath, mismatches);

    // then
    assertEquals(new HashSet<>(expectedMismatches), getMismatchClassSet(mismatches));
  }

  @TableTest({
    "scenario                            | methodName    | methodDesc                             | methodFlags                   | classToCheck  | expectedMismatches",
    "match method declared in class      | aMethod       | (Ljava/lang/String;)Ljava/lang/String; | []                            | B             | []                ",
    "match method declared in superclass | hashCode      | ()I                                    | []                            | B             | []                ",
    "match method declared in interface  | someMethod    | ()V                                    | []                            | SomeInterface | []                ",
    "match private method                | privateStuff  | ()V                                    | []                            | B             | []                ",
    "fail match private in supertype     | privateStuff  | ()V                                    | [EXPECTS_PUBLIC_OR_PROTECTED] | B2            | [MissingFlag]     ",
    "static method mismatch              | aStaticMethod | ()V                                    | [EXPECTS_NON_STATIC]          | B             | [MissingFlag]     ",
    "missing method mismatch             | missingMethod | ()V                                    | []                            | B             | [MissingMethod]   "
  })
  void methodMatch(
      String methodName,
      String methodDesc,
      List<Integer> methodFlags,
      Class<?> classToCheck,
      List<Class<?>> expectedMismatches) {
    Type methodType = Type.getMethodType(methodDesc);
    Reference reference =
        new Reference.Builder(classToCheck.getName())
            .withMethod(
                new String[0],
                flags(methodFlags),
                methodName,
                methodType.getReturnType(),
                methodType.getArgumentTypes())
            .build();
    List<Reference.Mismatch> mismatches = new ArrayList<>();

    // when
    ReferenceMatcher.checkReference(testTypePool, reference, testClasspath, mismatches);

    // then
    assertEquals(new HashSet<>(expectedMismatches), getMismatchClassSet(mismatches));
  }

  @TableTest({
    "scenario                            | fieldName      | fieldType                                                                 | fieldFlags                                    | classToCheck | expectedMismatches",
    "mismatch missing field              | missingField   | Ljava/lang/String;                                                        | []                                            | A            | [MissingField]    ",
    "mismatch field type signature       | privateField   | Ljava/lang/String;                                                        | []                                            | A            | [MissingField]    ",
    "match private field                 | privateField   | Ljava/lang/Object;                                                        | []                                            | A            | []                ",
    "mismatch private field in supertype | privateField   | Ljava/lang/Object;                                                        | [EXPECTS_PUBLIC_OR_PROTECTED]                 | A2           | [MissingFlag]     ",
    "mismatch static field               | protectedField | Ljava/lang/Object;                                                        | [EXPECTS_STATIC]                              | A            | [MissingFlag]     ",
    "match static field                  | staticB        | Ldatadog/trace/agent/tooling/muzzle/TestAdviceClasses$MethodBodyAdvice$B; | [EXPECTS_STATIC, EXPECTS_PUBLIC_OR_PROTECTED] | A            | []                "
  })
  void fieldMatch(
      String fieldName,
      String fieldType,
      List<Integer> fieldFlags,
      Class<?> classToCheck,
      List<Class<?>> expectedMismatches) {
    Reference reference =
        new Reference.Builder(classToCheck.getName())
            .withField(new String[0], flags(fieldFlags), fieldName, Type.getType(fieldType))
            .build();
    List<Reference.Mismatch> mismatches = new ArrayList<>();

    // when
    ReferenceMatcher.checkReference(testTypePool, reference, testClasspath, mismatches);

    // then
    assertEquals(new HashSet<>(expectedMismatches), getMismatchClassSet(mismatches));
  }

  /** Combines the flags of a table cell into a single flags value. */
  private static int flags(List<Integer> flagValues) {
    int flags = 0;
    for (int flag : flagValues) {
      flags |= flag;
    }
    return flags;
  }

  @TypeConverter
  public static Integer toFlag(String name) {
    switch (name) {
      case "EXPECTS_INTERFACE":
        return EXPECTS_INTERFACE;
      case "EXPECTS_NON_INTERFACE":
        return EXPECTS_NON_INTERFACE;
      case "EXPECTS_NON_STATIC":
        return EXPECTS_NON_STATIC;
      case "EXPECTS_PUBLIC_OR_PROTECTED":
        return EXPECTS_PUBLIC_OR_PROTECTED;
      case "EXPECTS_STATIC":
        return EXPECTS_STATIC;
      default:
        throw new IllegalArgumentException("Unknown Reference flag: " + name);
    }
  }

  @TypeConverter
  public static Class<?> toClass(String name) {
    switch (name) {
      case "A":
        return MethodBodyAdvice.A.class;
      case "A2":
        return MethodBodyAdvice.A2.class;
      case "B":
        return MethodBodyAdvice.B.class;
      case "B2":
        return MethodBodyAdvice.B2.class;
      case "SomeInterface":
        return MethodBodyAdvice.SomeInterface.class;
      case "MissingField":
        return Reference.Mismatch.MissingField.class;
      case "MissingFlag":
        return Reference.Mismatch.MissingFlag.class;
      case "MissingMethod":
        return Reference.Mismatch.MissingMethod.class;
      default:
        throw new IllegalArgumentException("Unknown test class: " + name);
    }
  }

  private static Set<Class<?>> getMismatchClassSet(List<Reference.Mismatch> mismatches) {
    Set<Class<?>> mismatchClasses = new HashSet<>(mismatches.size());
    for (Reference.Mismatch mismatch : mismatches) {
      mismatchClasses.add(mismatch.getClass());
    }
    return mismatchClasses;
  }
}
