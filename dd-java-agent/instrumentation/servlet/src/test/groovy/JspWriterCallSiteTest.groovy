import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.XssModule
import foo.bar.TestJspWriterSuite

import javax.servlet.jsp.JspWriter

class JspWriterCallSiteTest extends AgentTestRunner{

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
    expected * iastModule.onXss(args[0])
    0 * iastModule._

    where:
    method | args | expected
    "printTest" | [STRING]  | 1
    "printlnTest" | [STRING]  | 1
    "write" | [STRING]  | 1
    "printTest" | [CHAR_ARRAY]  | 0
    "printlnTest" | [CHAR_ARRAY]  | 0
    "write" | [CHAR_ARRAY]  | 0
  }
}
