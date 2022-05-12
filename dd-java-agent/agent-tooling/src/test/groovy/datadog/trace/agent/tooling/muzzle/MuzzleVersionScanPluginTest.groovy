package datadog.trace.agent.tooling.muzzle

import datadog.trace.agent.tooling.Instrumenter
import datadog.trace.agent.tooling.bytebuddy.DDCachingPoolStrategy
import datadog.trace.test.util.DDSpecification
import net.bytebuddy.matcher.ElementMatcher

import static datadog.trace.agent.tooling.muzzle.HelperClass.NestedHelperClass
import static datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.AdviceParameter
import static datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.AdviceReference
import static datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.AdviceStaticReference
import static datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.BaseInst
import static datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.BasicInst
import static datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.EmptyInst
import static datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.HelperInst
import static datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.InvalidMissingHelperInst
import static datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.InvalidOrderHelperInst
import static datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.SomeAdvice
import static datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.ValidHelperInst

class MuzzleVersionScanPluginTest extends DDSpecification {
  static {
    DDCachingPoolStrategy.registerAsSupplier()
  }

  def "test assertInstrumentationMuzzled advice"() {
    setup:
    def instrumentationLoader = new ServiceEnabledClassLoader(Instrumenter, Instrumenter.Default,
      ElementMatcher, ReferenceMatcher, IReferenceMatcher, Reference, ReferenceCreator)
    instrumentationLoader.addClass(TestInstrumentationClasses)
    instrumentationLoader.addClass(BaseInst)
    instCP.each { instrumentationLoader.addClass(it) }
    def userClassLoader = new AddableClassLoader(TestInstrumentationClasses)
    userCP.each { userClassLoader.addClass(it) }

    expect:
    MuzzleVersionScanPlugin.assertInstrumentationMuzzled(instrumentationLoader, userClassLoader, assertPass)

    where:
    // spotless:off
    assertPass | instCP                                                           | userCP
    true       | [EmptyInst]                                                      | []
    false      | [BasicInst, SomeAdvice]                                          | []
    false      | [BasicInst, SomeAdvice]                                          | [AdviceParameter, AdviceStaticReference]
    false      | [BasicInst, SomeAdvice]                                          | [AdviceParameter, AdviceReference]
    true       | [BasicInst, SomeAdvice]                                          | [AdviceParameter, AdviceReference, AdviceStaticReference]
    false      | [HelperInst, SomeAdvice, AdviceReference, AdviceStaticReference] | [] // Matching applies before helpers are injected
    // FIXME: AdviceParameter and AdviceMethodReturn are not validated
    // spotless:on
  }

  def "verify advice match failure"() {
    setup:
    def instrumentationLoader = new ServiceEnabledClassLoader(Instrumenter, Instrumenter.Default,
      ElementMatcher, ReferenceMatcher, IReferenceMatcher, Reference, ReferenceCreator)
    instrumentationLoader.addClass(TestInstrumentationClasses)
    instrumentationLoader.addClass(BaseInst)
    instCP.each { instrumentationLoader.addClass(it) }
    def userClassLoader = new AddableClassLoader(TestInstrumentationClasses)

    when:
    MuzzleVersionScanPlugin.assertInstrumentationMuzzled(instrumentationLoader, userClassLoader, true)

    then:
    def ex = thrown(RuntimeException)
    ex.message == "Instrumentation failed Muzzle validation"

    where:
    // spotless:off
    instCP                  | _
    [BasicInst, SomeAdvice] | _
    // spotless:on
  }

  def "test assertInstrumentationMuzzled helpers"() {
    setup:
    def instrumentationLoader = new ServiceEnabledClassLoader(Instrumenter, Instrumenter.Default, BaseInst,
      ElementMatcher, ReferenceMatcher, IReferenceMatcher, Reference, ReferenceCreator, inst)
    helpers.each { instrumentationLoader.addClass(it) }
    def userClassLoader = new AddableClassLoader()

    expect:
    MuzzleVersionScanPlugin.assertInstrumentationMuzzled(instrumentationLoader, userClassLoader, true)
    !helpers.findAll {
      userClassLoader.loadClass(it.name) != null
    }.isEmpty()

    where:
    // spotless:off
    inst                   | helpers
    ValidHelperInst        | [HelperClass, NestedHelperClass]
    InvalidOrderHelperInst | [HelperClass, NestedHelperClass]
    // spotless:on
  }

  def "test nested helpers failure"() {
    setup:
    def instrumentationLoader = new ServiceEnabledClassLoader(Instrumenter, Instrumenter.Default, BaseInst,
      ElementMatcher, ReferenceMatcher, IReferenceMatcher, Reference, ReferenceCreator, inst)
    helpers.each { instrumentationLoader.addClass(it) }
    def userClassLoader = new AddableClassLoader()

    when:
    MuzzleVersionScanPlugin.assertInstrumentationMuzzled(instrumentationLoader, userClassLoader, true)

    then:
    def ex = thrown(IllegalArgumentException)
    ex.message == "Nested helper $NestedHelperClass.name must have the parent class $HelperClass.name also defined as a helper"

    where:
    // spotless:off
    inst                     | helpers
    InvalidMissingHelperInst | [NestedHelperClass]
    // spotless:on
  }
}
