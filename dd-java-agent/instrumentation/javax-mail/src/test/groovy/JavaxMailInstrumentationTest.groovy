import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.EmailInjectionModule

import javax.mail.Address
import javax.mail.Message
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

    // mock the actual sending process
    mockedTransport.connect() >> {  }
    mockedTransport.close() >> {  }
    mockedTransport.sendMessage(_ as Message, _ as Address) >> {  }

    when:
    mockedTransport.send(message)

    then:
    assert module.onSendEmail(message)

    where:
    value <<
      new MimeMessage(Session.getDefaultInstance(new Properties())) { {
          setContent("<html><body>Hello, Content!</body></html>", "text/html")
        }
      }
  }
}
