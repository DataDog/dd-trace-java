package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.StringModule
import foo.bar.TestStringBufferSuite
import foo.bar.TestStringBuilderSuite

class StringBuilderCallSiteTest extends InstrumentationSpecification {

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
      1 * iastModule.onStringBuilderAppend(target, { it -> it.toString() == param.toString() } )
    }
    _ * TEST_PROFILING_CONTEXT_INTEGRATION._
    0 * _

    where:
    suite                        | target        | param         | expected
    new TestStringBuilderSuite() | sb('Hello ')  | 23.5F         | 'Hello 23.5'
    new TestStringBuilderSuite() | sb('Hello ')  | sbf('World!') | 'Hello World!'
    new TestStringBuilderSuite() | sb('Hello ')  | 'World!'      | 'Hello World!'
    new TestStringBufferSuite()  | sbf('Hello ') | 23.5F         | 'Hello 23.5'
    new TestStringBufferSuite()  | sbf('Hello ') | sbf('World!') | 'Hello World!'
    new TestStringBufferSuite()  | sbf('Hello ') | 'World!'      | 'Hello World!'
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

  void 'test string builder append call site with start and end'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    suite.append(target, param, start, end)

    then:
    target.toString() == expected
    1 * iastModule.onStringBuilderAppend(target, param, start, end)
    0 * _

    where:
    suite                        | target        | param    | start | end | expected
    new TestStringBuilderSuite() | sb('Hello ')  | 'World!' | 0     | 5   | 'Hello World'
    new TestStringBufferSuite()  | sbf('Hello ') | 'World!' | 0     | 5   | 'Hello World'
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
    _ * TEST_PROFILING_CONTEXT_INTEGRATION._
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
    _ * TEST_PROFILING_CONTEXT_INTEGRATION._
    0 * _
    final ex = thrown(NuclearException)
    ex.stackTrace.find { it.className == StringBuilderCallSite.name } == null
  }

  def 'test string #type substring call site'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = suite.substring(param, beginIndex)

    then:
    result == expected
    1 * iastModule.onStringSubSequence(param, beginIndex, param.length(), expected)
    0 * _

    where:
    type      | suite                        | param         | beginIndex | expected
    "builder" | new TestStringBuilderSuite() | sb('012345')  | 1          | '12345'
    "buffer"  | new TestStringBufferSuite()  | sbf('012345') | 1          | '12345'
  }

  def 'test string #type substring with endIndex call site'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = suite.substring(param, beginIndex, endIndex)

    then:
    result == expected
    1 * iastModule.onStringSubSequence(param, beginIndex, endIndex, expected)
    0 * _

    where:
    type      | suite                        | param         | beginIndex | endIndex | expected
    "builder" | new TestStringBuilderSuite() | sb('012345')  | 1          | 5        | '1234'
    "buffer"  | new TestStringBufferSuite()  | sbf('012345') | 1          | 5        | '1234'
  }

  def 'test string #type subSequence with endIndex call site'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = suite.subSequence(param, beginIndex, endIndex)

    then:
    result == expected
    1 * iastModule.onStringSubSequence(param, beginIndex, endIndex, expected)
    0 * _

    where:
    type      | suite                        | param         | beginIndex | endIndex | expected
    "builder" | new TestStringBuilderSuite() | sb('012345')  | 1          | 5        | '1234'
    "buffer"  | new TestStringBufferSuite()  | sbf('012345') | 1          | 5        | '1234'
  }

  def 'test string #type setLength with length: #length call site'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    suite.setLength(param, length)

    then:
    param.toString() == expected
    1 * iastModule.onStringBuilderSetLength(param, length)
    0 * _

    where:
    type      | suite                        | param         | length | expected
    "builder" | new TestStringBuilderSuite() | sb('012345')  | 5      | '01234'
    "buffer"  | new TestStringBufferSuite()  | sbf('012345') | 5      | '01234'
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

  private static StringBuilder sb(final String string) {
    return new StringBuilder(string)
  }

  private static StringBuffer sbf(final String string) {
    return new StringBuffer(string)
  }
}
