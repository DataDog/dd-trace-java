import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.EmailInjectionModule
import jakarta.mail.Provider
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.InternetAddress
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMultipart


class JakartaMailInstrumentationTest extends InstrumentationSpecification {

  @Override
  void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }


  void 'test jakarta mail Message HTML text'() {
    given:
    final module = Mock(EmailInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    def session = Session.getDefaultInstance(new Properties())
    def provider = new Provider(Provider.Type.TRANSPORT, "smtp", MockTransport.name, "MockTransport", "1.0")
    session.setProvider(provider)
    final message = new MimeMessage(session)
    message.setRecipient(Message.RecipientType.TO, new InternetAddress("mock@datadoghq.com"))
    message.setText(content, "utf-8", mimetype)

    when:
    Transport.send(message)

    then:
    1 * module.onSendEmail(message.getContent())

    where:
    mimetype  | content
    "html"    | "<html><body>Hello, Content!</body></html>"
  }

  void 'test jakarta mail Message Plain text'() {
    given:
    final module = Mock(EmailInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    def session = Session.getDefaultInstance(new Properties())
    def provider = new Provider(Provider.Type.TRANSPORT, "smtp", MockTransport.name, "MockTransport", "1.0")
    session.setProvider(provider)
    final message = new MimeMessage(session)
    message.setRecipient(Message.RecipientType.TO, new InternetAddress("mock@datadoghq.com"))
    message.setText(content, "utf-8", mimetype)

    when:
    Transport.send(message)

    then:
    0 * module.onSendEmail(message.getContent())

    where:
    mimetype  | content
    "plain"    | "<html><body>Hello, Content!</body></html>"
  }

  void 'test jakarta mail Message Content'() {
    given:
    final module = Mock(EmailInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    def session = Session.getDefaultInstance(new Properties())
    def provider = new Provider(Provider.Type.TRANSPORT, "smtp", MockTransport.name, "MockTransport", "1.0")
    session.setProvider(provider)
    final message = new MimeMessage(session)
    message.setRecipient(Message.RecipientType.TO, new InternetAddress("mock@datadoghq.com"))

    MimeMultipart content = new MimeMultipart()
    content.addBodyPart(new MimeBodyPart())
    content.addBodyPart(new MimeBodyPart())
    content.getBodyPart(0).setContent(body[0], "text/plain")
    content.getBodyPart(1).setContent(body[1], "text/html")
    message.setContent(content, mimetype)

    when:
    Transport.send(message)

    then:
    0 * module.onSendEmail(((MimeMultipart)message.getContent()).getBodyPart(0).getContent())
    1 * module.onSendEmail(((MimeMultipart)message.getContent()).getBodyPart(1).getContent())

    where:
    mimetype | body
    "multipart/*" | new String[]{
      "<html><body>Hello, Content!</body></html>", "<html><body>Evil Content!</body></html>"
    }
  }
}
