package datadog.trace.instrumentation.java.io


import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.XssModule
import org.apache.catalina.connector.CoyoteWriter
import org.apache.catalina.connector.OutputBuffer

class PrintWriterInstrumentationTest extends BaseIoCallSiteTest {

  void 'test write'(final PrintWriter pw, final int expected) {
    setup:
    XssModule module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)
    final String s = 'test'
    final char[] buf = 'test'.toCharArray()
    final int off = 0
    final int len = 1

    when:
    pw.write(s)

    then:
    expected * module.onXss(s)
    0 * _

    when:
    pw.write(s, off, len)

    then:
    expected * module.onXss(s)
    0 * _

    when:
    pw.write(buf, off, len)

    then:
    expected * module.onXss(buf)
    0 * _

    where:
    pw                                   | expected
    new PrintWriter("Test")              | 0
    new CoyoteWriter(Mock(OutputBuffer)) | 1
  }

  void 'test println'(final PrintWriter pw) {
    setup:
    XssModule module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)
    final String s = 'test'
    final char[] buf = 'test'.toCharArray()

    when:
    pw.println(s)

    then:
    expected * module.onXss(s)
    expected * module.onXss('\n') //due to CoyoteWriter println implementation
    0 * _

    when:
    pw.println(buf)

    then:
    expected * module.onXss(buf)
    expected * module.onXss('\n') //due to CoyoteWriter println implementation
    0 * _

    where:
    pw                                   | expected
    new PrintWriter("Test")              | 0
    new CoyoteWriter(Mock(OutputBuffer)) | 1
  }

  void 'test print'(final PrintWriter pw) {
    setup:
    XssModule module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)
    final char[] buf = 'test'.toCharArray()

    when:
    pw.print(buf)

    then:
    expected * module.onXss(buf)
    0 * _

    where:
    pw                                   | expected
    new PrintWriter("Test")              | 0
    new CoyoteWriter(Mock(OutputBuffer)) | 1
  }


  void 'test printf'(final PrintWriter pw) {
    setup:
    XssModule module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)
    final s = '"Formatted like: %1$s and %2$s."'
    final Object[] args = ['a', 'b']


    when:
    pw.printf(Locale.getDefault(), s, args)

    then:
    if (expected) {
      1 * module.onXss('"Formatted like: ')
      1 * module.onXss('a')
      1 * module.onXss(' and ')
      1 * module.onXss('b')
      1 * module.onXss('."')
    } else {
      0 * module.onXss(_ as String)
    }
    0 * _

    when:
    pw.printf(s, args)

    then:
    if (expected) {
      1 * module.onXss('"Formatted like: ')
      1 * module.onXss('a')
      1 * module.onXss(' and ')
      1 * module.onXss('b')
      1 * module.onXss('."')
    } else {
      0 * module.onXss(_ as String)
    }
    0 * _

    where:
    pw                                   | expected
    new PrintWriter("Test")              | false
    new CoyoteWriter(Mock(OutputBuffer)) | true
  }

  void 'test format'(final PrintWriter pw) {
    setup:
    XssModule module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)
    final s = '"Formatted like: %1$s and %2$s."'
    final Object[] args = ['a', 'b']

    when:
    pw.format(Locale.getDefault(), s, args)

    then:
    if (expected) {
      1 * module.onXss('"Formatted like: ')
      1 * module.onXss('a')
      1 * module.onXss(' and ')
      1 * module.onXss('b')
      1 * module.onXss('."')
    } else {
      0 * module.onXss(_ as String)
    }
    0 * _

    when:
    pw.format(s, args)

    then:
    if (expected) {
      1 * module.onXss('"Formatted like: ')
      1 * module.onXss('a')
      1 * module.onXss(' and ')
      1 * module.onXss('b')
      1 * module.onXss('."')
    } else {
      0 * module.onXss(_ as String)
    }
    0 * _

    where:
    pw                                   | expected
    new PrintWriter("Test")              | false
    new CoyoteWriter(Mock(OutputBuffer)) | true
  }
}
