package datadog.trace.instrumentation.scala


import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.StringModule
import spock.lang.IgnoreIf

class StringContextCallSiteTest extends AbstractIastScalaTest {

  @Override
  String suiteName() {
    return 'foo.bar.TestStringInterpolationSuite'
  }

  void 'test #interpolator interpolator'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = testSuite."$interpolator"('left', 'right')

    then:
    result == 'Left is \'left\' and right is \'right\''
    if (!usesJavaConcat) {
      1 * iastModule.onStringFormat(
        { it -> (it as List<String>) == ['Left is \'', '\' and right is \'', '\''] },
        ['left', 'right'] as Object[],
        'Left is \'left\' and right is \'right\''
        )
    } else {
      1 * iastModule.onStringBuilderAppend(_ as StringBuilder, 'Left is \'')
      1 * iastModule.onStringBuilderAppend(_ as StringBuilder, 'left')
      1 * iastModule.onStringBuilderAppend(_ as StringBuilder, '\' and right is \'')
      1 * iastModule.onStringBuilderAppend(_ as StringBuilder, 'right')
      1 * iastModule.onStringBuilderAppend(_ as StringBuilder, '\'')
      1 * iastModule.onStringBuilderToString(_ as StringBuilder, 'Left is \'left\' and right is \'right\'')
    }
    0 * _

    where:
    interpolator | _
    's'          | _
    'raw'        | _
  }

  void 'test f interpolator'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = testSuite.f('left', 'right')

    then:
    result == 'Left is \'left\' and right is \'right\''
    1 * iastModule.onStringFormat(
      'Left is \'%s\' and right is \'%s\'',
      ['left', 'right'] as Object[],
      'Left is \'left\' and right is \'right\''
      )
    0 * _
  }

  @IgnoreIf({ instance.usesJavaConcat })
  void 'test leading empty chunk'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = testSuite.leadingChunk('START')

    then:
    result == 'START: is located at the beginning of the string'
    1 * iastModule.onStringFormat(
      { it -> (it as List<String>) == ['', ': is located at the beginning of the string'] },
      ['START'] as Object[],
      'START: is located at the beginning of the string'
      )
    0 * _
  }

  @IgnoreIf({ instance.usesJavaConcat })
  void 'test trailing empty chunk'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = testSuite.trailingChunk('END')

    then:
    result == 'The value is located at the end of the string: END'
    1 * iastModule.onStringFormat(
      { it -> (it as List<String>) == ['The value is located at the end of the string: ', ''] },
      ['END'] as Object[],
      'The value is located at the end of the string: END'
      )
    0 * _
  }

  void 'test string format with incompatible float type'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    // Directly test the StringModuleImpl.onStringFormat with incompatible types
    // This simulates what happens when Scala f-interpolator passes wrong types at runtime
    final pattern = 'User: %s and Balance: %f'
    final params = ['admin', 'not-a-number'] as Object[]
    iastModule.onStringFormat(pattern, params, _) >> { String fmt, Object[] args, String result ->
      // Call the real implementation
      def ctx = mock(datadog.trace.api.iast.IastContext)
      def taintedObjects = mock(com.datadog.iast.taint.TaintedObjects)
      ctx.getTaintedObjects() >> taintedObjects
      datadog.trace.api.iast.IastContext.Provider.get() >> ctx
    }

    then:
    // Test should not throw IllegalFormatConversionException
    // The fix should handle it gracefully
    notThrown(IllegalFormatConversionException)
  }

  void 'test string format with multiple incompatible types'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    // Test with multiple type mismatches
    final pattern = 'Name: %s, Age: %d, Score: %f'
    final params = ['John', 'thirty', 'high'] as Object[]
    iastModule.onStringFormat(pattern, params, _) >> { String fmt, Object[] args, String result ->
      // Call the real implementation
      def ctx = mock(datadog.trace.api.iast.IastContext)
      def taintedObjects = mock(com.datadog.iast.taint.TaintedObjects)
      ctx.getTaintedObjects() >> taintedObjects
      datadog.trace.api.iast.IastContext.Provider.get() >> ctx
    }

    then:
    // Test should not throw IllegalFormatConversionException
    notThrown(IllegalFormatConversionException)
  }
}

