import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.XssModule
import foo.bar.smoketest.TestJspWriterSuite

import jakarta.servlet.jsp.JspWriter

class JakartaJspWriterCallsiteTest extends AgentTestRunner{

  static final STRING = "test"
  static final CHAR_ARRAY = STRING.toCharArray()

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test JspWriter'() {
    setup:
    final iastModule = Mock(XssModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final writer = Mock(JspWriter)
    final suite = new TestJspWriterSuite(writer)

    when:
    suite.&"$method".call(args)

    then:
    1 * iastModule.onXss(args[0])
    0 * iastModule._

    where:
    method | args
    "printTest" | [STRING]
    "printlnTest" | [STRING]
    "write" | [STRING]
    "write" | [STRING, 0, 0]
    "printTest" | [CHAR_ARRAY]
    "printlnTest" | [CHAR_ARRAY]
    "write" | [CHAR_ARRAY]
    "write" | [CHAR_ARRAY, 0, 0]
  }
}
