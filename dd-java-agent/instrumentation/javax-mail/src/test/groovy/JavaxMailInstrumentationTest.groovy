import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.EmailInjectionModule

import javax.mail.Transport
import javax.mail.internet.MimeMessage


class JavaxMailInstrumentationTest  extends AgentTestRunner {
  @Override
  void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }


  void 'test javax.mail.Message'(Object value) {
    given:
    final module = Mock(EmailInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    final mockedTransport = Mock(Transport)
    final message = value

    when:
    mockedTransport.send(message)

    then:
    1 * module.onSendEmail(message)

    where:
    value << [new MimeMessage(null), new MimeMessage(null)]
  }
}
