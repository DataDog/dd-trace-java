import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.EmailInjectionModule
import javax.mail.Transport
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import de.saly.javamail.mock2.MockTransport


class JavaxMailInstrumentationTest  extends AgentTestRunner {
  @Override
  void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def setupSpec() {
    System.setProperty("mail.smtp.class", MockTransport.getName())
  }

  void 'test javax.mail.Message text'() {
    given:
    final module = Mock(EmailInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    final session = Session.getInstance(new Properties())
    final message = new MimeMessage(session)
    message.setRecipient(Message.RecipientType.TO, new InternetAddress("mock@datadoghq.com"))
    message.setContent(content, mimetype)

    when:
    Transport.send(message)

    then:
    1 * module.onSendEmail(message.getContent())

    where:
    mimetype | content
    "text/html" | "<html><body>Hello, Content!</body></html>"
  }

  void 'test javax.mail.Message Content'() {
    given:
    final module = Mock(EmailInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    final session = Session.getInstance(new Properties())
    final message = new MimeMessage(session)
    message.setRecipient(Message.RecipientType.TO, new InternetAddress("mock@datadoghq.com"))
    message.setContent(content, mimetype)


    when:
    Transport.send(message)

    then:
    1 * module.onSendEmail(message.getContent())

    where:
    mimetype | content
    "text/html" | "<html><body>Hello, Content!</body></html>"
  }

  void 'test javax.mail.Message simple text'() {
    given:
    final module = Mock(EmailInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    final session = Session.getInstance(new Properties())
    final message = new MimeMessage(session)
    message.setRecipient(Message.RecipientType.TO, new InternetAddress("mock@datadoghq.com"))
    message.setContent(content, mimetype)

    when:
    Transport.send(message)

    then:
    1 * module.onSendEmail(message.getContent())

    where:
    mimetype | content
    "text/html" | "<html><body>Hello, Content!</body></html>"
  }

  void 'test javax.mail.Message sanitized Text'() {
    given:
    final module = Mock(EmailInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    final session = Session.getInstance(new Properties())
    final message = new MimeMessage(session)
    message.setRecipient(Message.RecipientType.TO, new InternetAddress("mock@datadoghq.com"))
    message.setContent(content, mimetype)

    when:
    Transport.send(message)

    then:
    1 * module.onSendEmail(message.getContent())

    where:
    mimetype | content
    "text/html" | "<html><body>Hello, Content!</body></html>"
  }

  void 'test javax.mail.Message sanitized Object'() {
    given:
    final module = Mock(EmailInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    final session = Session.getInstance(new Properties())
    final message = new MimeMessage(session)
    message.setRecipient(Message.RecipientType.TO, new InternetAddress("mock@datadoghq.com"))
    message.setContent(content, mimetype)

    when:
    Transport.send(message)

    then:
    1 * module.onSendEmail(message.getContent())

    where:
    mimetype | content
    "text/html" | "<html><body>Hello, Content!</body></html>"
  }
}
