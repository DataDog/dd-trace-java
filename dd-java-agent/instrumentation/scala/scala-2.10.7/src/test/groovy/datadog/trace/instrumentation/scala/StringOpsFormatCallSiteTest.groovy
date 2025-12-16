package datadog.trace.instrumentation.scala

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.StringModule

/**
 * Tests for StringOpsCallSite.unwrapScalaNumbers functionality.
 *
 * These tests verify that Scala ScalaNumber types (BigDecimal, BigInt) are properly
 * unwrapped to their underlying Java representations (java.math.BigDecimal, java.math.BigInteger)
 * before being passed to IAST's onStringFormat.
 *
 * This prevents IllegalFormatConversionException and ensures correct taint tracking.
 */
class StringOpsFormatCallSiteTest extends AbstractIastScalaTest {

  @Override
  String suiteName() {
    return 'foo.bar.TestStringOpsFormatSuite'
  }

  void 'test formatBigDecimal with scala.math.BigDecimal'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = testSuite.formatBigDecimal('123.456')

    then:
    result == 'Value: 123.456000'
    // Verify that the unwrapped java.math.BigDecimal is passed, not scala.math.BigDecimal
    1 * iastModule.onStringFormat(
      'Value: %f', { Object[] args ->
        args.length == 1 &&
          args[0] != null &&
          args[0].getClass() == BigDecimal &&
          args[0].toString() == '123.456'
      },
      'Value: 123.456000'
      )
    0 * iastModule.onUnexpectedException(_, _)
  }

  void 'test formatBigInt with scala.math.BigInt'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = testSuite.formatBigInt('12345')

    then:
    result == 'Count: 12345'
    // Verify that the unwrapped java.math.BigInteger is passed, not scala.math.BigInt
    1 * iastModule.onStringFormat(
      'Count: %d', { Object[] args ->
        args.length == 1 &&
          args[0] != null &&
          args[0].getClass() == BigInteger &&
          args[0].toString() == '12345'
      },
      'Count: 12345'
      )
    0 * iastModule.onUnexpectedException(_, _)
  }

  void 'test formatMultipleScalaNumbers with BigDecimal and BigInt'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = testSuite.formatMultipleScalaNumbers('99.99', '42')

    then:
    result == 'Decimal: 99.990000, Integer: 42'
    // Verify that both ScalaNumbers are unwrapped
    1 * iastModule.onStringFormat(
      'Decimal: %f, Integer: %d', { Object[] args ->
        args.length == 2 &&
          args[0] != null &&
          args[0].getClass() == BigDecimal &&
          args[0].toString() == '99.99' &&
          args[1] != null &&
          args[1].getClass() == BigInteger &&
          args[1].toString() == '42'
      },
      'Decimal: 99.990000, Integer: 42'
      )
    0 * iastModule.onUnexpectedException(_, _)
  }

  void 'test formatMixed with BigDecimal and String'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = testSuite.formatMixed('3.14', 'hello')

    then:
    result == 'Value: 3.140000, Text: hello'
    // Verify that BigDecimal is unwrapped but String remains unchanged
    1 * iastModule.onStringFormat(
      'Value: %f, Text: %s', { Object[] args ->
        args.length == 2 &&
          args[0] != null &&
          args[0].getClass() == BigDecimal &&
          args[0].toString() == '3.14' &&
          args[1] instanceof String &&
          args[1] == 'hello'
      },
      'Value: 3.140000, Text: hello'
      )
    0 * iastModule.onUnexpectedException(_, _)
  }

  void 'test formatString with regular String arguments (no unwrapping)'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = testSuite.formatString('left', 'right')

    then:
    result == 'Left: left, Right: right'
    // Verify that String arguments are passed unchanged (no unwrapping needed)
    1 * iastModule.onStringFormat(
      'Left: %s, Right: %s', { Object[] args ->
        args.length == 2 &&
          args[0] instanceof String &&
          args[0] == 'left' &&
          args[1] instanceof String &&
          args[1] == 'right'
      },
      'Left: left, Right: right'
      )
    0 * iastModule.onUnexpectedException(_, _)
  }
}
