import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.EmailInjectionModule

import javax.mail.Transport


class JavaxMailInstrumentationTest  extends AgentTestRunner {
  @Override
  void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }


  void 'test javax.mail.Message'() {
    given:
    final module = Mock(EmailInjectionModule)
    InstrumentationBridge.registerIastModule(module)


    when:
    Transport.send(message)

    then:
    1 * module.onSendEmail(message)
  }
}
