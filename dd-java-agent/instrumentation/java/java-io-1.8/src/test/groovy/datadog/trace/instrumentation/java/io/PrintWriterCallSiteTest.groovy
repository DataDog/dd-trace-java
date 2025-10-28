package datadog.trace.instrumentation.java.io

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.XssModule
import foo.bar.TestPrintWriterSuite
import org.apache.catalina.connector.CoyoteWriter

class PrintWriterCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test  #method #args #clazz'() {
    setup:
    XssModule module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)
    final testPrintWriterSuite = new TestPrintWriterSuite(Mock(clazz))

    when:
    testPrintWriterSuite.&"$method".call(args.toArray())

    then:
    1 * module.onXss(args.get(0))
    0 * module._

    where:
    clazz        | method    | args
    PrintWriter  | 'write'   | ['test']
    PrintWriter  | 'write'   | ['test'.toCharArray()]
    PrintWriter  | 'write'   | ['test', 0, 1]
    PrintWriter  | 'write'   | ['test'.toCharArray(), 0, 1]
    PrintWriter  | 'println' | ['test']
    PrintWriter  | 'println' | ['test'.toCharArray()]
    PrintWriter  | 'print'   | ['test']
    PrintWriter  | 'print'   | ['test'.toCharArray()]
    CoyoteWriter | 'write'   | ['test']
    CoyoteWriter | 'write'   | ['test'.toCharArray()]
    CoyoteWriter | 'write'   | ['test', 0, 1]
    CoyoteWriter | 'write'   | ['test'.toCharArray(), 0, 1]
    CoyoteWriter | 'println' | ['test']
    CoyoteWriter | 'println' | ['test'.toCharArray()]
    CoyoteWriter | 'print'   | ['test']
    CoyoteWriter | 'print'   | ['test'.toCharArray()]
  }

  void 'test  #method #args #clazz'() {
    setup:
    XssModule module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)
    final testPrintWriterSuite = new TestPrintWriterSuite(Mock(clazz))

    when:
    testPrintWriterSuite.&"$method".call(args.toArray())

    then:
    1 * module.onXss(args.get(1), args.get(2))
    0 * module._

    where:
    clazz        | method       | args
    PrintWriter  | 'testPrintf' | [Locale.getDefault(), '"Formatted like: %1$s and %2$s."', ['a', 'b']]
    PrintWriter  | 'format'     | [Locale.getDefault(), '"Formatted like: %1$s and %2$s."', ['a', 'b']]
    CoyoteWriter | 'testPrintf' | [Locale.getDefault(), '"Formatted like: %1$s and %2$s."', ['a', 'b']]
    CoyoteWriter | 'format'     | [Locale.getDefault(), '"Formatted like: %1$s and %2$s."', ['a', 'b']]
  }

  void 'test  #method #args #clazz'() {
    setup:
    XssModule module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)
    final testPrintWriterSuite = new TestPrintWriterSuite(Mock(clazz))

    when:
    testPrintWriterSuite.&"$method".call(args.toArray())

    then:
    1 * module.onXss(args.get(0), args.get(1))
    0 * module._

    where:
    clazz        | method       | args
    PrintWriter  | 'testPrintf' | ['"Formatted like: %1$s and %2$s."', ['a', 'b']]
    PrintWriter  | 'format'     | ['"Formatted like: %1$s and %2$s."', ['a', 'b']]
    CoyoteWriter | 'testPrintf' | ['"Formatted like: %1$s and %2$s."', ['a', 'b']]
    CoyoteWriter | 'format'     | ['"Formatted like: %1$s and %2$s."', ['a', 'b']]
  }
}
