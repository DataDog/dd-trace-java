package datadog.trace.agent.tooling.muzzle

import datadog.trace.agent.tooling.Instrumenter
import datadog.trace.agent.tooling.InstrumenterModule
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

  def "test assertInstrumentationMuzzled advice"() {
    setup:
    def instrumentationLoader = new ServiceEnabledClassLoader(InstrumenterModule,
      Instrumenter.HasMethodAdvice, ElementMatcher, ReferenceMatcher, Reference, ReferenceCreator)
    instrumentationLoader.addClass(TestInstrumentationClasses)
    instrumentationLoader.addClass(BaseInst)
    instCP.each { instrumentationLoader.addClass(it) }
    def testApplicationLoader = new AddableClassLoader(TestInstrumentationClasses)
    testCP.each { testApplicationLoader.addClass(it) }

    expect:
    MuzzleVersionScanPlugin.assertInstrumentationMuzzled(instrumentationLoader, testApplicationLoader, assertPass, null)

    where:
    // spotless:off
    assertPass | instCP                                                                              | testCP
    true       | [EmptyInst, EmptyInst.Muzzle]                                                       | []
    false      | [BasicInst, BasicInst.Muzzle, SomeAdvice]                                           | []
    false      | [BasicInst, BasicInst.Muzzle, SomeAdvice]                                           | [AdviceParameter, AdviceStaticReference]
    false      | [BasicInst, BasicInst.Muzzle, SomeAdvice]                                           | [AdviceParameter, AdviceReference]
    true       | [BasicInst, BasicInst.Muzzle, SomeAdvice]                                           | [AdviceParameter, AdviceReference, AdviceStaticReference]
    false      | [HelperInst, HelperInst.Muzzle, SomeAdvice, AdviceReference, AdviceStaticReference] | [] // Matching applies before helpers are injected
    // FIXME: AdviceParameter and AdviceMethodReturn are not validated
    // spotless:on
  }

  def "verify advice match failure"() {
    setup:
    def instrumentationLoader = new ServiceEnabledClassLoader(InstrumenterModule,
      Instrumenter.HasMethodAdvice, ElementMatcher, ReferenceMatcher, Reference, ReferenceCreator)
    instrumentationLoader.addClass(TestInstrumentationClasses)
    instrumentationLoader.addClass(BaseInst)
    instCP.each { instrumentationLoader.addClass(it) }
    def testApplicationLoader = new AddableClassLoader(TestInstrumentationClasses)

    when:
    MuzzleVersionScanPlugin.assertInstrumentationMuzzled(instrumentationLoader, testApplicationLoader, true, null)

    then:
    def ex = thrown(RuntimeException)
    ex.message == "Instrumentation failed Muzzle validation"

    where:
    // spotless:off
    instCP                                    | _
    [BasicInst, BasicInst.Muzzle, SomeAdvice] | _
    // spotless:on
  }

  def "test assertInstrumentationMuzzled helpers"() {
    setup:
    def instrumentationLoader = new ServiceEnabledClassLoader(InstrumenterModule, BaseInst,
      Instrumenter.HasMethodAdvice, ElementMatcher, ReferenceMatcher, Reference, ReferenceCreator, inst, muzzle)
    helpers.each { instrumentationLoader.addClass(it) }
    def testApplicationLoader = new AddableClassLoader()

    expect:
    MuzzleVersionScanPlugin.assertInstrumentationMuzzled(instrumentationLoader, testApplicationLoader, true, null)

    where:
    // spotless:off
    inst                   | muzzle                        | helpers
    ValidHelperInst        | ValidHelperInst.Muzzle        | [HelperClass, NestedHelperClass]
    InvalidOrderHelperInst | InvalidOrderHelperInst.Muzzle | [HelperClass, NestedHelperClass]
    // spotless:on
  }

  def "test nested helpers failure"() {
    setup:
    def instrumentationLoader = new ServiceEnabledClassLoader(InstrumenterModule, BaseInst,
      Instrumenter.HasMethodAdvice, ElementMatcher, ReferenceMatcher, Reference, ReferenceCreator, inst, muzzle)
    helpers.each { instrumentationLoader.addClass(it) }
    def testApplicationLoader = new AddableClassLoader()

    when:
    MuzzleVersionScanPlugin.assertInstrumentationMuzzled(instrumentationLoader, testApplicationLoader, true, null)

    then:
    def ex = thrown(IllegalArgumentException)
    ex.message == "Nested helper $NestedHelperClass.name must have the parent class $HelperClass.name also defined as a helper"

    where:
    // spotless:off
    inst                     | muzzle                        | helpers
    InvalidMissingHelperInst | InvalidOrderHelperInst.Muzzle | [NestedHelperClass]
    // spotless:on
  }
}
