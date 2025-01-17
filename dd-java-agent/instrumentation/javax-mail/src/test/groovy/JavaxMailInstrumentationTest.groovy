import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.EmailInjectionModule

import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.MimeMessage


class JavaxMailInstrumentationTest  extends AgentTestRunner {
  @Override
  void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }


  void 'test javax.mail.Message'(MimeMessage value) {
    given:
    final module = Mock(EmailInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    final mockedTransport = Mock(Transport)
    final message = value
    value.setRecipients(MimeMessage.RecipientType.TO, "sezen.leblay@datadoghq.com")

    when:
    mockedTransport.send(value)

    then:
    1 * module.onSendEmail(message)
    0 * _

    where:
    value                                                                     | _
    new MimeMessage(Session.getDefaultInstance(new Properties())) { {
          setContent("<html><body>Hello, World!</body></html>", "text/html")
        }
      }                                                                       | _
    new MimeMessage(Session.getDefaultInstance(new Properties())) { {
          setText("<html><body>Hello, World!</body></html>")
        }
      }                                                                       | _
  }
}
