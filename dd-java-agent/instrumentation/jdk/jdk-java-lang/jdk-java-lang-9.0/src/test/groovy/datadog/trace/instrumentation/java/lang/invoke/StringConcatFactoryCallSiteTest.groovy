package datadog.trace.instrumentation.java.lang.invoke

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.StringModule
import foo.bar.TestStringConcatFactorySuite
import spock.lang.Requires

import static foo.bar.TestStringConcatFactorySuite.stringPlusWithPrimitive

@Requires({
  jvm.java9Compatible
})
class StringConcatFactoryCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test string concat factory'() {
    setup:
    StringModule iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final expected = 'Hello World!'

    when:
    final result = TestStringConcatFactorySuite.plus('Hello ', 'World!')

    then:
    result == expected
    1 * iastModule.onStringConcatFactory(
      expected,
      ['Hello ', 'World!'] as String[],
      '\u0001\u0001',
      [] as Object[],
      [0, 1] as int[])
    0 * _
  }

  void 'test string concat factory with constants '() {
    setup:
    StringModule iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final expected = 'Hello World!'

    when:
    final result = TestStringConcatFactorySuite.plusWithConstants('Hello', 'World!')

    then:
    result == expected
    1 * iastModule.onStringConcatFactory(
      expected,
      ['Hello', 'World!'] as String[],
      '\u0001 \u0001',
      [] as Object[],
      [0, -1, 1] as int[])
    0 * _
  }

  void 'test string concat factory with flag constants'() {
    setup:
    StringModule iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final expected = '\u0001 Hello \u0002 World!.'

    when:
    final result = TestStringConcatFactorySuite.plusWithConstantsAndTags('Hello', 'World!')

    then:
    result == expected
    1 * iastModule.onStringConcatFactory(
      expected,
      ['Hello', 'World!'] as String[],
      '\u0002\u0001\u0002\u0001.',
      ['\u0001 ', ' \u0002 '] as Object[],
      [-2, 0, -3, 1, -1] as int[])
    0 * _
  }

  void 'test string concat factory with object args'() {
    setup:
    StringModule iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final web = new URL('https://www.datadoghq.com/')
    final Date date = new Date()
    final expected = "Come to my website ${web} today is ${date}"

    when:
    final result = TestStringConcatFactorySuite.plus('Come to my website ', web, ' today is ', date)

    then:
    result == expected
    1 * iastModule.onStringConcatFactory(
      expected,
      ['Come to my website ', web.toString(), ' today is ', date.toString()] as String[],
      '\u0001\u0001\u0001\u0001',
      [] as Object[],
      [0, 1, 2, 3] as int[])
    0 * _
  }

  void 'test string concat factory with null args'() {
    setup:
    StringModule iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final expected = 'Hello null'

    when:
    final result = TestStringConcatFactorySuite.plus('Hello ', null)

    then:
    result == expected
    1 * iastModule.onStringConcatFactory(
      expected,
      ['Hello ', 'null'] as String[],
      '\u0001\u0001',
      [] as Object[],
      [0, 1] as int[])
    0 * _
  }

  void 'test string concat factory with multiple args'() {
    setup:
    StringModule iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final args = ['Come to my website ', new URL('https://www.datadoghq.com/'), ' today is ', new Date()] as Object[]
    final first = ['', args[0]] as String[]
    final second = [args[0], args[1]] as String[]
    final third = [args[0..1].join(), args[2]] as String[]
    final fourth = [args[0..2].join(), args[3]] as String[]
    final expected = args.join()

    when:
    final result = TestStringConcatFactorySuite.stringPlusWithMultipleObjects(args)

    then:
    result == expected
    1 * iastModule.onStringConcatFactory(first.join(), first, '\u0001\u0001', [] as Object[], [0, 1] as int[])
    1 * iastModule.onStringConcatFactory(second.join(), second, '\u0001\u0001', [] as Object[], [0, 1] as int[])
    1 * iastModule.onStringConcatFactory(third.join(), third, '\u0001\u0001', [] as Object[], [0, 1] as int[])
    1 * iastModule.onStringConcatFactory(fourth.join(), fourth, '\u0001\u0001', [] as Object[], [0, 1] as int[])
    0 * _
  }

  void 'test string concat factory with utf constants'() {
    setup:
    StringModule iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final expected = '𠆢Hello𠆢\u0001𠆢World!.'

    when:
    final result = TestStringConcatFactorySuite.plusWithUtfConstants('Hello', 'World!')

    then:
    result == expected
    1 * iastModule.onStringConcatFactory(
      expected,
      ['Hello', 'World!'] as String[],
      '𠆢\u0001\u0002\u0001.',
      ['𠆢\u0001𠆢'] as Object[],
      [-2, 0, -5, 1, -1] as int[])
    0 * _
  }

  void 'test string concat factory with primitives #iterationIndex'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = method.call()

    then:
    result == expectedResult
    1 * iastModule.onStringConcatFactory(
      expectedResult,
      expectedArgs,
      '\u0001\u0001',
      [] as Object[],
      [0, 1] as int[])
    0 * _

    where:
    expectedResult           | expectedArgs                   | method
    'Hello World! in 2023'   | ['Hello World! in ', '2023']   | { it -> stringPlusWithPrimitive('Hello World! in ', (int) 2023) }
    'Give me a number : 5'   | ['Give me a number : ', '5']   | { it -> stringPlusWithPrimitive('Give me a number : ', (byte) 5) }
    'Give me a number : 5'   | ['Give me a number : ', '5']   | { it -> stringPlusWithPrimitive('Give me a number : ', (short) 5) }
    'Are you mad? false'     | ['Are you mad? ', 'false']     | { it -> stringPlusWithPrimitive('Are you mad? ', (boolean) false) }
    'Give me a letter : c'   | ['Give me a letter : ', 'c']   | { it -> stringPlusWithPrimitive('Give me a letter : ', (char) 'c') }
    'Hello World! in 2023'   | ['Hello World! in ', '2023']   | { it -> stringPlusWithPrimitive('Hello World! in ', (long) 2023) }
    'Hello World! in 2023.0' | ['Hello World! in ', '2023.0'] | { it -> stringPlusWithPrimitive('Hello World! in ', (float) 2023.0) }
    'Hello World! in 2023.0' | ['Hello World! in ', '2023.0'] | { it -> stringPlusWithPrimitive('Hello World! in ', (double) 2023.0) }
  }
}
