package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.StringModule
import foo.bar.TestStringBuilderSuite

class StringBuilderCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test string builder new call site'(final CharSequence param, final String expected) {
    setup:
    StringModule iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = param.class == String ?
      TestStringBuilderSuite.init((String) param) :
      TestStringBuilderSuite.init(param)

    then:
    result.toString() == expected
    if (param.class == String) {
      1 * iastModule.onStringBuilderInit(_ as StringBuilder, (String) param)
    } else {
      1 * iastModule.onStringBuilderInit(_ as StringBuilder, param)
    }
    0 * _

    where:
    param                            | expected
    new StringBuffer('Hello World!') | 'Hello World!'
    'Hello World!'                   | 'Hello World!'
  }

  def 'test string builder append call site'(final CharSequence param, final String expected) {
    setup:
    StringModule iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final target = new StringBuilder('Hello ')

    when:
    if (param.class == String) {
      TestStringBuilderSuite.append(target, (String) param)
    } else {
      TestStringBuilderSuite.append(target, param)
    }

    then:
    target.toString() == expected
    if (param.class == String) {
      1 * iastModule.onStringBuilderAppend(target, (String) param)
    } else {
      1 * iastModule.onStringBuilderAppend(target, param)
    }
    _ * TEST_CHECKPOINTER._
    0 * _

    where:
    param                      | expected
    new StringBuffer('World!') | 'Hello World!'
    'World!'                   | 'Hello World!'
  }

  def 'test string builder toString call site'() {
    setup:
    StringModule iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final target = new StringBuilder('Hello World!')

    when:
    final result = TestStringBuilderSuite.toString(target)

    then:
    result == 'Hello World!'
    1 * iastModule.onStringBuilderToString(target, _ as String)
    0 * _
  }

  def 'test string builder call site in plus operations (JDK8)'() {
    setup:
    StringModule iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = TestStringBuilderSuite.plus('Hello ', 'World!')

    then:
    result == 'Hello World!'
    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, 'Hello ')
    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, 'World!')
    1 * iastModule.onStringBuilderToString(_ as StringBuilder, 'Hello World!')
    _ * TEST_CHECKPOINTER._
    0 * _
  }

  def 'test string builder call site in plus operations with multiple objects (JDK8)'() {
    setup:
    StringModule iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final args = ['Come to my website ', new URL('https://www.datadoghq.com/'), ' today is ', new Date()] as Object[]
    final expected = args.join()

    when:
    final result = TestStringBuilderSuite.plus(args)

    then:
    result == expected

    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, '')
    1 * iastModule.onStringBuilderToString(_ as StringBuilder, args[0])

    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, args[0])
    1 * iastModule.onStringBuilderToString(_ as StringBuilder, args[0..1].join())

    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, args[0..1].join())
    1 * iastModule.onStringBuilderToString(_ as StringBuilder, args[0..2].join())

    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, args[0..2].join())
    1 * iastModule.onStringBuilderToString(_ as StringBuilder, expected)

    0 * _
  }

  def 'test string builder call site in plus operations throwing exceptions (JDK8)'() {
    setup:
    StringModule iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    TestStringBuilderSuite.plus('Hello', new BrokenToString())

    then:
    1 * iastModule.onStringBuilderAppend(_, 'Hello')
    _ * TEST_CHECKPOINTER._
    0 * _
    final ex = thrown(NuclearException)
    ex.stackTrace.find {it.className == StringBuilderCallSite.name } == null
  }

  private static class BrokenToString {
    @Override
    String toString() {
      throw new NuclearException('BOOM!!!!')
    }
  }

  private static class NuclearException extends RuntimeException {
    NuclearException(final String message) {
      super(message)
    }
  }
}
