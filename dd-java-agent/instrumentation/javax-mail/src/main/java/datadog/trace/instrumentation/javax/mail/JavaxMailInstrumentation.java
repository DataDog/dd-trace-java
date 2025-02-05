package datadog.trace.instrumentation.javax.mail;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.EmailInjectionModule;
import java.io.IOException;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public class JavaxMailInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static Logger LOGGER = LoggerFactory.getLogger(JavaxMailInstrumentation.class);

  public JavaxMailInstrumentation() {
    super("javax-mail", "javax-mail-transport");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("send0").and(takesArgument(0, named("javax.mail.Message"))),
        JavaxMailInstrumentation.class.getName() + "$MailInjectionAdvice");
  }

  @Override
  public String instrumentedType() {
    return "javax.mail.Transport";
  }

  public static class MailInjectionAdvice {
    @Sink(VulnerabilityTypes.EMAIL_HTML_INJECTION)
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onSend(@Advice.Argument(0) final Message message)
        throws MessagingException, IOException {
      EmailInjectionModule emailInjectionModule = InstrumentationBridge.EMAIL_INJECTION;
      if (emailInjectionModule == null) {
        return;
      }
      if (message == null || message.getContent() == null) {
        return;
      }
      if (message.isMimeType("text/html")) {
        emailInjectionModule.onSendEmail(message.getContent());
      } else if (message.isMimeType("multipart/*")) {
        Multipart parts = (Multipart) message.getContent();
        for (int i = 0; i < parts.getCount(); i++) {
          final Part part = parts.getBodyPart(i);
          if (part != null && part.isMimeType("text/html")) {
            emailInjectionModule.onSendEmail(part.getContent());
          }
        }
      }
    }
  }
}
