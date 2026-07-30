package datadog.trace.instrumentation.beanshell

import bsh.Interpreter
import bsh.Remote
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.CodeInjectionModule
import datadog.trace.api.iast.sink.SsrfModule

class BeanShellInstrumentationTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test Interpreter.eval(String)'() {
    given:
    final module = Mock(CodeInjectionModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    new Interpreter().eval('2 + 2;')

    then:
    // eval(String) delegates to eval(String, NameSpace) (inspected directly as a String) which then
    // builds a StringReader and delegates to the eval(Reader, NameSpace, String) core (inspected as a
    // Reader). Both advices fire here; in production the internally built reader is untainted (bsh.* is
    // excluded from call-site instrumentation), so only the String check can actually report.
    1 * module.onEval('2 + 2;')
    1 * module.onEval(_ as Reader)
    0 * _
  }

  void 'test Interpreter.eval(Reader)'() {
    given:
    final module = Mock(CodeInjectionModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    new Interpreter().eval(new StringReader('2 + 2;'))

    then:
    1 * module.onEval(_ as Reader)
    0 * _
  }

  void 'test Remote.eval routes text to code injection and connecting url schemes to ssrf'() {
    given:
    final codeInjectionModule = Mock(CodeInjectionModule)
    final ssrfModule = Mock(SsrfModule)
    InstrumentationBridge.registerIastModule(codeInjectionModule)
    InstrumentationBridge.registerIastModule(ssrfModule)

    when:
    // Remote.eval is OnMethodEnter-instrumented, so the sinks fire before the body attempts a
    // connection; the subsequent connection failure to a dead port is expected and irrelevant here.
    try {
      Remote.eval(url, '2 + 2;')
    } catch (Exception ignored) {
    }

    then:
    1 * codeInjectionModule.onEval('2 + 2;')
    ssrfCalls * ssrfModule.onURLConnection(url)
    0 * _

    where:
    // bsh.Remote connects only for the "http:" and "bsh:" schemes; every other scheme throws
    // before any I/O, so no SSRF should be reported for it.
    url                      | ssrfCalls
    'bsh://localhost:1/'     | 1
    'http://localhost:1/'    | 1
    'https://localhost:1/'   | 0
    'ftp://localhost:1/'     | 0
  }
}
