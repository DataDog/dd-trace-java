package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.StringModule
import foo.bar.TestStringBufferSuite
import foo.bar.TestStringBuilderSuite

class StringBuilderCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test string builder new call site'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = param.class == String ? suite.init((String) param) : suite.init(param)

    then:
    result.toString() == expected
    if (param.class == String) {
      1 * iastModule.onStringBuilderInit(_, (String) param)
    } else {
      1 * iastModule.onStringBuilderInit(_, param)
    }
    0 * _

    where:
    suite                        | param                             | expected
    new TestStringBuilderSuite() | new StringBuffer('Hello World!')  | 'Hello World!'
    new TestStringBuilderSuite() | 'Hello World!'                    | 'Hello World!'
    new TestStringBufferSuite()  | new StringBuilder('Hello World!') | 'Hello World!'
    new TestStringBufferSuite()  | 'Hello World!'                    | 'Hello World!'
  }

  void 'test string builder append call site'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    if (param.class == String) {
      suite.append(target, (String) param)
    } else if (param.class == CharSequence) {
      suite.append(target, (CharSequence) param)
    } else {
      suite.append(target, param)
    }

    then:
    target.toString() == expected
    if (param.class == String) {
      1 * iastModule.onStringBuilderAppend(target, (String) param)
    } else {
      1 * iastModule.onStringBuilderAppend(target, param.toString())
    }
    _ * TEST_CHECKPOINTER._
    0 * _

    where:
    suite                        | target                      | param                       | expected
    new TestStringBuilderSuite() | new StringBuilder('Hello ') | 23.5F                       | 'Hello 23.5'
    new TestStringBuilderSuite() | new StringBuilder('Hello ') | new StringBuffer('World!')  | 'Hello World!'
    new TestStringBuilderSuite() | new StringBuilder('Hello ') | 'World!'                    | 'Hello World!'
    new TestStringBufferSuite()  | new StringBuffer('Hello ')  | 23.5F                       | 'Hello 23.5'
    new TestStringBufferSuite()  | new StringBuffer('Hello ')  | new StringBuilder('World!') | 'Hello World!'
    new TestStringBufferSuite()  | new StringBuffer('Hello ')  | 'World!'                    | 'Hello World!'
  }

  void 'test string builder append object throwing exceptions'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    suite.append(target, new BrokenToString())

    then:
    final ex = thrown(NuclearException)
    ex.stackTrace.find { it.className == StringBuilderCallSite.name } == null

    where:
    suite                        | target
    new TestStringBuilderSuite() | new StringBuilder('Hello ')
    new TestStringBufferSuite()  | new StringBuffer('Hello ')
  }

  void 'test string builder toString call site'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = suite.toString(target)

    then:
    result == 'Hello World!'
    1 * iastModule.onStringBuilderToString(target, _ as String)
    0 * _

    where:
    suite                        | target
    new TestStringBuilderSuite() | new StringBuilder('Hello World!')
    new TestStringBufferSuite()  | new StringBuffer('Hello World!')
  }

  void 'test string builder call site in plus operations (JDK8)'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = new TestStringBuilderSuite().plus('Hello ', 'World!')

    then:
    result == 'Hello World!'
    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, 'Hello ')
    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, 'World!')
    1 * iastModule.onStringBuilderToString(_ as StringBuilder, 'Hello World!')
    _ * TEST_CHECKPOINTER._
    0 * _
  }

  void 'test string builder call site in plus operations with multiple objects (JDK8)'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final args = ['Come to my website ', new URL('https://www.datadoghq.com/'), ' today is ', new Date()] as Object[]
    final expected = args.join()

    when:
    final result = new TestStringBuilderSuite().plus(args)

    then:
    result == expected

    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, '')
    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, args[0])
    1 * iastModule.onStringBuilderToString(_ as StringBuilder, args[0])

    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, args[0])
    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, args[1].toString())
    1 * iastModule.onStringBuilderToString(_ as StringBuilder, args[0..1].join())

    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, args[0..1].join())
    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, args[2])
    1 * iastModule.onStringBuilderToString(_ as StringBuilder, args[0..2].join())

    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, args[0..2].join())
    1 * iastModule.onStringBuilderAppend(_ as StringBuilder, args[3].toString())
    1 * iastModule.onStringBuilderToString(_ as StringBuilder, args[0..3].join())

    0 * _
  }

  void 'test string builder call site in plus operations throwing exceptions (JDK8)'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    new TestStringBuilderSuite().plus('Hello', new BrokenToString())

    then:
    1 * iastModule.onStringBuilderAppend(_, 'Hello')
    _ * TEST_CHECKPOINTER._
    0 * _
    final ex = thrown(NuclearException)
    ex.stackTrace.find { it.className == StringBuilderCallSite.name } == null
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
