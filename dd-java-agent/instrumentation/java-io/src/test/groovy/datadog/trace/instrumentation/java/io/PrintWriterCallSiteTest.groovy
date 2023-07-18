package datadog.trace.instrumentation.java.io

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.XssModule
import foo.bar.TestPrintWriterSuite
import org.apache.catalina.connector.CoyoteWriter

class PrintWriterCallSiteTest extends BaseIoCallSiteTest {

  void 'test write'() {
    setup:
    XssModule module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)
    final String s = 'test'
    final char[] buf = 'test'.toCharArray()
    final int off = 0
    final int len = 1
    final testPrintWriterSuite = new TestPrintWriterSuite(Mock(clazz))

    when:
    testPrintWriterSuite.write(s)

    then:
    expected * module.onXss(s)
    0 * module._


    when:
    testPrintWriterSuite.write(s, off, len)

    then:
    expected * module.onXss(s)
    0 * module._


    when:
    testPrintWriterSuite.write(buf, off, len)

    then:
    expected * module.onXss(buf)
    0 * module._

    when:
    testPrintWriterSuite.write(buf)

    then:
    expected * module.onXss(buf)
    0 * module._

    where:
    clazz        | expected
    PrintWriter  | 1
    CoyoteWriter | 1
  }

  void 'test println'() {
    setup:
    XssModule module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)
    final String s = 'test'
    final char[] buf = 'test'.toCharArray()
    final testPrintWriterSuite = new TestPrintWriterSuite(Mock(clazz))

    when:
    testPrintWriterSuite.println(s)

    then:
    expected * module.onXss(s)
    0 * module._

    when:
    testPrintWriterSuite.println(buf)

    then:
    expected * module.onXss(buf)
    0 * module._

    where:
    clazz        | expected
    PrintWriter  | 1
    CoyoteWriter | 1
  }

  void 'test print'() {
    setup:
    XssModule module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)
    final String s = 'test'
    final char[] buf = 'test'.toCharArray()
    final testPrintWriterSuite = new TestPrintWriterSuite(Mock(clazz))

    when:
    testPrintWriterSuite.print(s)

    then:
    expected * module.onXss(s)
    0 * module._

    when:
    testPrintWriterSuite.print(buf)

    then:
    expected * module.onXss(buf)
    0 * module._

    where:
    clazz        | expected
    PrintWriter  | 1
    CoyoteWriter | 1
  }


  void 'test printf'() {
    setup:
    XssModule module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)
    final format = '"Formatted like: %1$s and %2$s."'
    final Object[] args = ['a', 'b']
    final testPrintWriterSuite = new TestPrintWriterSuite(Mock(clazz))

    when:
    testPrintWriterSuite.printf(Locale.getDefault(), format, args)

    then:
    expected * module.onXss(format, args)
    0 * module._

    when:
    testPrintWriterSuite.printf(format, args)

    then:
    expected * module.onXss(format, args)
    0 * module._

    where:
    clazz        | expected
    PrintWriter  | 1
    CoyoteWriter | 1
  }


  void 'test format'() {
    setup:
    XssModule module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)
    final format = '"Formatted like: %1$s and %2$s."'
    final Object[] args = ['a', 'b']
    final testPrintWriterSuite = new TestPrintWriterSuite(Mock(clazz))

    when:
    testPrintWriterSuite.format(Locale.getDefault(), format, args)

    then:
    expected * module.onXss(format, args)
    0 * module._

    when:
    testPrintWriterSuite.format(format, args)

    then:
    expected * module.onXss(format, args)
    0 * module._

    where:
    clazz        | expected
    PrintWriter  | 1
    CoyoteWriter | 1
  }
}
