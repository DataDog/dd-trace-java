package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.StringModule
import foo.bar.TestStringSuite

import static datadog.trace.api.config.IastConfig.IAST_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_EXPERIMENTAL_PROPAGATION_ENABLED

class StringExperimentalCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig(IAST_ENABLED, "true")
    injectSysConfig(IAST_EXPERIMENTAL_PROPAGATION_ENABLED, "true")
  }

  void 'test string replace char sequence'() {
    given:
    final module = Mock(StringModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestStringSuite.replace(input, oldCharSeq, newCharSeq)

    then:
    1 * module.onStringReplace(input, oldCharSeq, newCharSeq)

    where:
    input  | oldCharSeq | newCharSeq
    "test" | 'te'       | 'TE'
    "test" | 'es'       | 'ES'
  }

  void 'test string replace char sequence (throw error)'() {
    given:
    final module = Mock(StringModule)
    InstrumentationBridge.registerIastModule(module)
    module.onStringReplace(_ as String, _ as CharSequence, _ as CharSequence) >> { throw new Error("test error") }

    when:
    def result = TestStringSuite.replace(input, oldCharSeq, newCharSeq)

    then:
    result == expected
    1 * module.onUnexpectedException("afterReplaceCharSeq threw", _ as Error)

    where:
    input  | oldCharSeq | newCharSeq | expected
    "test" | 'te'       | 'TE'       | 'TEst'
    "test" | 'es'       | 'ES'       | 'tESt'
  }

  void 'test string replace all and replace first with regex'() {
    given:
    final module = Mock(StringModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestStringSuite."$method"(input, regex, replacement)

    then:
    1 * module.onStringReplace(input, regex, replacement, numReplacements)

    where:
    method         | input  | regex | replacement | numReplacements
    "replaceAll"   | "test" | 'te'  | 'TE'        | Integer.MAX_VALUE
    "replaceAll"   | "test" | 'es'  | 'ES'        | Integer.MAX_VALUE
    "replaceFirst" | "test" | 'te'  | 'TE'        | 1
    "replaceFirst" | "test" | 'es'  | 'ES'        | 1
  }

  void 'test string replace all and replace first with regex (throw error)'() {
    given:
    final module = Mock(StringModule)
    InstrumentationBridge.registerIastModule(module)
    module.onStringReplace(_ as String, _ as String, _ as String, numReplacements) >> { throw new Error("test error") }
    final textError = "afterR" + method.substring(1) + " threw"

    when:
    def result = TestStringSuite."$method"(input, regex, replacement)

    then:
    result == expected
    1 * module.onUnexpectedException(textError, _ as Error)

    where:
    method         | input  | regex | replacement | numReplacements   | expected
    "replaceAll"   | "test" | 'te'  | 'TE'        | Integer.MAX_VALUE | 'TEst'
    "replaceAll"   | "test" | 'es'  | 'ES'        | Integer.MAX_VALUE | 'tESt'
    "replaceFirst" | "test" | 'te'  | 'TE'        | 1                 | 'TEst'
    "replaceFirst" | "test" | 'es'  | 'ES'        | 1                 | 'tESt'
  }
}
