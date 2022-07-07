package datadog.trace.agent.tooling.muzzle

import datadog.trace.agent.test.utils.ClasspathUtils
import datadog.trace.agent.tooling.bytebuddy.SharedTypePools
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers
import datadog.trace.agent.tooling.muzzle.TestAdviceClasses.MethodBodyAdvice
import datadog.trace.test.util.DDSpecification
import net.bytebuddy.jar.asm.Type
import net.bytebuddy.pool.TypePool
import spock.lang.Shared

import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_INTERFACE
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_NON_INTERFACE
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_NON_STATIC
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_PUBLIC_OR_PROTECTED
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_STATIC
import static datadog.trace.agent.tooling.muzzle.Reference.Mismatch.MissingClass
import static datadog.trace.agent.tooling.muzzle.Reference.Mismatch.MissingField
import static datadog.trace.agent.tooling.muzzle.Reference.Mismatch.MissingFlag
import static datadog.trace.agent.tooling.muzzle.Reference.Mismatch.MissingMethod

class ReferenceMatcherTest extends DDSpecification {
  static {
    SharedTypePools.registerIfAbsent(SharedTypePools.simpleCache())
    HierarchyMatchers.registerIfAbsent(HierarchyMatchers.simpleChecks())
  }

  @Shared
  ClassLoader safeClasspath = new URLClassLoader([
    ClasspathUtils.createJarWithClasses(MethodBodyAdvice.A,
    MethodBodyAdvice.B,
    MethodBodyAdvice.SomeInterface,
    MethodBodyAdvice.SkipLevel, // pattern in e.g. AWS SDK where empty interfaces join other interfaces
    MethodBodyAdvice.HasMethod,
    MethodBodyAdvice.SomeImplementation)
  ] as URL[],
  (ClassLoader) null)

  @Shared
  ClassLoader unsafeClasspath = new URLClassLoader([
    ClasspathUtils.createJarWithClasses(MethodBodyAdvice.A,
    MethodBodyAdvice.SomeInterface,
    MethodBodyAdvice.SomeImplementation)
  ] as URL[],
  (ClassLoader) null)

  @Shared
  ClassLoader testClasspath = this.getClass().getClassLoader()

  @Shared
  TypePool testTypePool = SharedTypePools.typePool(testClasspath)

  def "match safe classpaths"() {
    setup:
    Reference[] refs = ReferenceCreator.createReferencesFrom(MethodBodyAdvice.getName(), testClasspath).values().toArray(new Reference[0])
    ReferenceMatcher refMatcher = new ReferenceMatcher(refs)

    expect:
    getMismatchClassSet(refMatcher.getMismatchedReferenceSources(safeClasspath)) == new HashSet<>()
    getMismatchClassSet(refMatcher.getMismatchedReferenceSources(unsafeClasspath)) == new HashSet<>([MissingClass])
  }

  def "matching does not hold a strong reference to classloaders"() {
    expect:
    MuzzleWeakReferenceTest.classLoaderRefIsGarbageCollected()
  }

  private static class CountingClassLoader extends URLClassLoader {
    int count = 0

    CountingClassLoader(URL[] urls, ClassLoader parent) {
      super(urls, (ClassLoader) parent)
    }

    @Override
    URL getResource(String name) {
      count++
      return super.getResource(name)
    }
  }

  def "muzzle results are cached"() {
    setup:
    ClassLoader cl = new CountingClassLoader(
      [
        ClasspathUtils.createJarWithClasses(MethodBodyAdvice.A,
        MethodBodyAdvice.B,
        MethodBodyAdvice.SomeInterface,
        MethodBodyAdvice.SkipLevel,
        MethodBodyAdvice.HasMethod,
        MethodBodyAdvice.SomeImplementation)
      ] as URL[],
      (ClassLoader) null)
    Reference[] refs = ReferenceCreator.createReferencesFrom(MethodBodyAdvice.getName(), testClasspath).values().toArray(new Reference[0])
    ReferenceMatcher refMatcher = new ReferenceMatcher(refs)
    assert refMatcher.matches(cl)
    int countAfterFirstMatch = cl.count
    // re-running the muzzle matcher against the same classloader should use the result cache
    assert refMatcher.matches(cl)

    expect:
    cl.count == countAfterFirstMatch
  }

  def "matching ref #referenceName #referenceFlags against #classToCheck produces #expectedMismatches"() {
    setup:
    Reference.Builder builder = new Reference.Builder(referenceName)
    builder = builder.withFlag(referenceFlags)
    Reference ref = builder.build()
    List<Reference.Mismatch> mismatches = new ArrayList<>()

    when:
    ReferenceMatcher.checkReference(testTypePool, ref, testClasspath, mismatches)

    then:
    getMismatchClassSet(mismatches) == expectedMismatches as Set

    where:
    // spotless:off
    referenceName                | referenceFlags        | classToCheck       | expectedMismatches
    MethodBodyAdvice.B.getName() | EXPECTS_NON_INTERFACE | MethodBodyAdvice.B | []
    MethodBodyAdvice.B.getName() | EXPECTS_INTERFACE     | MethodBodyAdvice.B | [MissingFlag]
    // spotless:on
  }

  def "method match #methodTestDesc"() {
    setup:
    Type methodType = Type.getMethodType(methodDesc)
    Reference reference = new Reference.Builder(classToCheck.getName())
      .withMethod(new String[0], methodFlags, methodName, methodType.getReturnType(), methodType.getArgumentTypes())
      .build()
    List<Reference.Mismatch> mismatches = new ArrayList<>()


    when:
    ReferenceMatcher.checkReference(testTypePool, reference, testClasspath, mismatches)

    then:
    getMismatchClassSet(mismatches) == expectedMismatches as Set

    where:
    // spotless:off
    methodName      | methodDesc                               | methodFlags                 | classToCheck                   | expectedMismatches | methodTestDesc
    "aMethod"       | "(Ljava/lang/String;)Ljava/lang/String;" | 0                           | MethodBodyAdvice.B             | []                 | "match method declared in class"
    "hashCode"      | "()I"                                    | 0                           | MethodBodyAdvice.B             | []                 | "match method declared in superclass"
    "someMethod"    | "()V"                                    | 0                           | MethodBodyAdvice.SomeInterface | []                 | "match method declared in interface"
    "privateStuff"  | "()V"                                    | 0                           | MethodBodyAdvice.B             | []                 | "match private method"
    "privateStuff"  | "()V"                                    | EXPECTS_PUBLIC_OR_PROTECTED | MethodBodyAdvice.B2            | [MissingFlag]      | "fail match private in supertype"
    "aStaticMethod" | "()V"                                    | EXPECTS_NON_STATIC          | MethodBodyAdvice.B             | [MissingFlag]      | "static method mismatch"
    "missingMethod" | "()V"                                    | 0                           | MethodBodyAdvice.B             | [MissingMethod]    | "missing method mismatch"
    // spotless:on
  }

  def "field match #fieldTestDesc"() {
    setup:
    Reference reference = new Reference.Builder(classToCheck.getName())
      .withField(new String[0], fieldFlags, fieldName, Type.getType(fieldType))
      .build()
    List<Reference.Mismatch> mismatches = new ArrayList<>()

    when:
    ReferenceMatcher.checkReference(testTypePool, reference, testClasspath, mismatches)

    then:
    getMismatchClassSet(mismatches) == expectedMismatches as Set

    where:
    // spotless:off
    fieldName        | fieldType                                        | fieldFlags                                   | classToCheck        | expectedMismatches | fieldTestDesc
    "missingField"   | "Ljava/lang/String;"                             | 0                                            | MethodBodyAdvice.A  | [MissingField]     | "mismatch missing field"
    "privateField"   | "Ljava/lang/String;"                             | 0                                            | MethodBodyAdvice.A  | [MissingField]     | "mismatch field type signature"
    "privateField"   | "Ljava/lang/Object;"                             | 0                                            | MethodBodyAdvice.A  | []                 | "match private field"
    "privateField"   | "Ljava/lang/Object;"                             | EXPECTS_PUBLIC_OR_PROTECTED                  | MethodBodyAdvice.A2 | [MissingFlag]      | "mismatch private field in supertype"
    "protectedField" | "Ljava/lang/Object;"                             | EXPECTS_STATIC                               | MethodBodyAdvice.A  | [MissingFlag]      | "mismatch static field"
    "staticB"        | Type.getType(MethodBodyAdvice.B).getDescriptor() | EXPECTS_STATIC + EXPECTS_PUBLIC_OR_PROTECTED | MethodBodyAdvice.A  | []                 | "match static field"
    // spotless:on
  }

  private static Set<Class> getMismatchClassSet(List<Reference.Mismatch> mismatches) {
    final Set<Class> mismatchClasses = new HashSet<>(mismatches.size())
    for (Reference.Mismatch mismatch : mismatches) {
      mismatchClasses.add(mismatch.getClass())
    }
    return mismatchClasses
  }
}
