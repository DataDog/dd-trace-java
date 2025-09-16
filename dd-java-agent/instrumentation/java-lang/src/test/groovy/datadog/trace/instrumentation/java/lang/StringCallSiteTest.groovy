package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.StringModule
import foo.bar.TestStringSuite
import groovy.transform.CompileDynamic

import static datadog.trace.api.config.IastConfig.IAST_ENABLED

@CompileDynamic
class StringCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig(IAST_ENABLED, "true")
  }

  def 'test string concat call site'() {
    setup:
    StringModule stringModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(stringModule)

    when:
    final result = TestStringSuite.concat('Hello ', 'World!')

    then:
    result == 'Hello World!'
    1 * stringModule.onStringConcat('Hello ', 'World!', 'Hello World!')
    0 * _
  }

  def 'test string toUpperCase call site'() {
    setup:
    final stringModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(stringModule)

    when:
    final result = TestStringSuite.stringToUpperCase('hello', null)
    final result2 = TestStringSuite.stringToUpperCase('world', new Locale("en"))

    then:
    result == 'HELLO'
    result2 == 'WORLD'
    1 * stringModule.onStringToUpperCase('hello', 'HELLO')
    1 * stringModule.onStringToUpperCase('world', 'WORLD')
    0 * _
  }

  def 'test string toLowerCase call site'() {
    setup:
    final stringModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(stringModule)

    when:
    final result = TestStringSuite.stringToLowerCase('HELLO', null)
    final result2 = TestStringSuite.stringToLowerCase('WORLD', new Locale("en"))

    then:
    result == 'hello'
    result2 == 'world'
    1 * stringModule.onStringToLowerCase('HELLO', 'hello')
    1 * stringModule.onStringToLowerCase('WORLD', 'world')
    0 * _
  }

  def 'test string substring call site'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final self = '012345'
    final expected = '12345'

    when:
    final result = TestStringSuite.substring(self, 1)

    then:
    result == expected
    1 * iastModule.onStringSubSequence(self, 1, self.length(), expected)
    0 * _
  }

  def 'test string substring with endIndex call site'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final self = '012345'
    final expected = '1234'

    when:
    final result = TestStringSuite.substring(self, 1, 5)

    then:
    result == expected
    1 * iastModule.onStringSubSequence(self, 1, 5, expected)
    0 * _
  }

  def 'test string subSequence call site'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final self = '012345'
    final expected = '1234'

    when:
    final result = TestStringSuite.subSequence(self, 1, 5)

    then:
    result == expected
    1 * iastModule.onStringSubSequence(self, 1, 5, expected)
    0 * _
  }

  def 'test string join call site'() {
    setup:
    final iastModule = Mock(StringModule)
    final expected = '012-345'
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = TestStringSuite.join('-', '012', '345')

    then:
    result == expected
    1 * iastModule.onStringJoin(expected, '-', '012', '345')
    0 * _
  }

  def 'test string join with Iterable call site'() {
    setup:
    final iastModule = Mock(StringModule)
    final expected = '012-345'
    InstrumentationBridge.registerIastModule(iastModule)
    final iterable = Arrays.asList('012', '345')

    when:
    final result = TestStringSuite.join('-', iterable)

    then:
    result == expected
    1 * iastModule.onStringJoin(expected, '-', '012', '345')
    0 * _
  }

  def 'test string join with Iterable fail on iterable copy'() {

    given:
    final iterable = Mock(Iterable)

    when:
    TestStringSuite.join('-', iterable)

    then:
    1 * iterable.forEach(_) >> { throw new Error('Boom!!!') }
    thrown Error
  }

  def 'test string trim call site'() {
    setup:
    final module = Mock(StringModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final result = TestStringSuite.stringTrim(' hello ')

    then:
    result == 'hello'
    1 * module.onStringTrim(' hello ', 'hello')
    0 * _
  }

  def 'test string Constructor call site'() {
    setup:
    final module = Mock(StringModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final result = TestStringSuite.stringConstructor(param)

    then:
    result == expected
    1 * module.onStringConstructor(_, _)
    0 * _

    where:
    param                      | expected
    'hello'                    | 'hello'
    new StringBuilder('hello') | 'hello'
    new StringBuffer('hello')  | 'hello'
  }

  void 'test string format'() {
    given:
    final module = Mock(StringModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestStringSuite.format(pattern, args == null ? null : args as Object[])

    then:
    1 * module.onStringFormat(pattern, args, _ as String)

    where:
    pattern | args
    ''      | []
    ''      | null
    '%s'    | ['Hello']
  }

  void 'test string format with locale'() {
    given:
    final module = Mock(StringModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestStringSuite.format(Locale.getDefault(), pattern, args == null ? null : args as Object[])

    then:
    1 * module.onStringFormat(_, pattern, args, _ as String)

    where:
    locale              | pattern | args
    null                | ''      | []
    null                | ''      | null
    null                | '%s'    | ['Hello']
    Locale.getDefault() | '%s'    | ['Hello']
  }

  void 'test string split'() {
    final module = Mock(StringModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final String[] result = TestStringSuite.&split.call(args)

    then:
    result != null && result.length == expected.length
    for (def i = 0; i < expected.length; i++) {
      result[i] == expected[i]
    }
    1 * module.onSplit(args[0], _ as String[])
    0 * _

    where:
    args                      | expected
    ['test the test', ' ']    | ['test', 'the', 'test'] as String[]
    ['test the test', ' ', 0] | ['test', 'the', 'test'] as String[]
  }

  void 'test string replace char'() {
    given:
    final module = Mock(StringModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    def result = TestStringSuite.replace(input, oldChar as char, newChar as char)

    then:
    result == expected
    1 * module.onStringReplace(input, oldChar, newChar, expected)

    where:
    input  | oldChar | newChar | expected
    "test" | 't'     | 'T'     | "TesT"
    "test" | 'e'     | 'E'     | "tEst"
  }

  def 'test string valueOf call site'() {
    setup:
    final stringModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(stringModule)

    when:
    final result = TestStringSuite.valueOf(input)

    then:
    result == expected
    1 * stringModule.onStringValueOf(input, expected)
    0 * _

    where:
    input                     | expected
    "test"                    | "test"
    new StringBuilder("test") | "test"
    new StringBuffer("test")  | "test"
  }
}
