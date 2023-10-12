package datadog.trace.instrumentation.scala

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.StringModule
import scala.collection.mutable.StringBuilder as ScalaStringBuilder

import java.lang.StringBuilder as JavaStringBuilder

class StringBuilderCallSiteTest extends AbstractIastScalaTest {

  @Override
  String suiteName() {
    return 'foo.bar.TestScalaStringBuilderSuite'
  }

  void 'test string builder new call site'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = testSuite.&init.call(args as Object[])

    then:
    result.toString() == expected
    1 * iastModule.onStringBuilderInit(_ as CharSequence, 'Hello World!')
    0 * _

    where:
    args                 | expected
    ['Hello World!']     | 'Hello World!'
    [12, 'Hello World!'] | 'Hello World!'
  }

  void 'test string builder append call site'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    if (param.class == String) {
      testSuite.append(target, (String) param)
    } else if (param.class == ScalaStringBuilder) {
      testSuite.append(target, (ScalaStringBuilder) param)
    } else {
      testSuite.append(target, param)
    }

    then:
    target.toString() == expected
    1 * iastModule.onStringBuilderAppend(_ as CharSequence, _)
    0 * _

    where:
    target                   | param                                  | expected
    new ScalaStringBuilder() | new ScalaStringBuilder('Hello World!') | 'Hello World!'
    new ScalaStringBuilder() | new JavaStringBuilder('Hello World!')  | 'Hello World!'
    new ScalaStringBuilder() | 'Hello World!'                         | 'Hello World!'
  }

  void 'test string builder toString call site'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = testSuite.toString(target)

    then:
    result == 'Hello World!'
    1 * iastModule.onStringBuilderToString(_ as CharSequence, 'Hello World!')
    0 * _

    where:
    target                                 | _
    new ScalaStringBuilder('Hello World!') | _
  }

  void 'test string builder call site in plus operations (JDK8)'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = testSuite.plus('Hello ', 'World!')

    then:
    result == 'Hello World!'
    1 * iastModule.onStringBuilderAppend(_ as CharSequence, 'Hello ')
    1 * iastModule.onStringBuilderAppend(_ as CharSequence, 'World!')
    1 * iastModule.onStringBuilderToString(_ as CharSequence, 'Hello World!')
    0 * _
  }

  void 'test string builder call site in plus operations with multiple objects (JDK8)'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final args = ['Come to my website ', new URL('https://www.datadoghq.com/'), ' today is ', new Date()] as Object[]
    final expected = args.join()

    when:
    final result = testSuite.plus(args)

    then:
    result == expected

    1 * iastModule.onStringBuilderAppend(_ as CharSequence, '')
    1 * iastModule.onStringBuilderAppend(_ as CharSequence, args[0])
    1 * iastModule.onStringBuilderToString(_ as CharSequence, args[0])

    1 * iastModule.onStringBuilderAppend(_ as CharSequence, args[0])
    1 * iastModule.onStringBuilderAppend(_ as CharSequence, args[1].toString())
    1 * iastModule.onStringBuilderToString(_ as CharSequence, args[0..1].join())

    1 * iastModule.onStringBuilderAppend(_ as CharSequence, args[0..1].join())
    1 * iastModule.onStringBuilderAppend(_ as CharSequence, args[2])
    1 * iastModule.onStringBuilderToString(_ as CharSequence, args[0..2].join())

    1 * iastModule.onStringBuilderAppend(_ as CharSequence, args[0..2].join())
    1 * iastModule.onStringBuilderAppend(_ as CharSequence, args[3].toString())
    1 * iastModule.onStringBuilderToString(_ as CharSequence, args[0..3].join())

    0 * _
  }
}

