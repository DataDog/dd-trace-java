package datadog.trace.instrumentation.java.lang.invoke

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.CodecModule
import datadog.trace.api.iast.propagation.StringModule
import foo.bar.TestLambdaMetaFactorySuite

import java.nio.charset.Charset

class LambdaMetaFactoryCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void setup() {
    InstrumentationBridge.registerIastModule(Mock(StringModule))
    InstrumentationBridge.registerIastModule(Mock(CodecModule))
  }

  void 'test that metaFactory method is instrumented for function'() {
    when:
    final result = TestLambdaMetaFactorySuite.getBytes('Hello World!')

    then:
    result == 'Hello World!'.bytes
    1 * InstrumentationBridge.CODEC.onStringGetBytes(_, _, _)
  }

  void 'test that metaFactory method is instrumented for bifunction'() {
    when:
    final result = TestLambdaMetaFactorySuite.concat('Hello ', 'World!')

    then:
    result == 'Hello World!'
    1 * InstrumentationBridge.STRING.onStringConcat(_, _, _)
  }

  void 'test that metaFactory method is instrumented for custom interface'() {
    given:
    final value = 'Hello World!'

    when:
    final result = TestLambdaMetaFactorySuite.decode(value.bytes, 0, value.length(), Charset.defaultCharset().name())

    then:
    result == value
    1 * InstrumentationBridge.CODEC.onStringFromBytes(_, _, _, _)
  }

  void 'test alt metaFactory method'() {
    when:
    final result = TestLambdaMetaFactorySuite.altMetaFactory('Hello ', 'World!')

    then:
    result == 'Hello World!'
    1 * InstrumentationBridge.STRING.onStringConcat(_, _, _)
  }
}
