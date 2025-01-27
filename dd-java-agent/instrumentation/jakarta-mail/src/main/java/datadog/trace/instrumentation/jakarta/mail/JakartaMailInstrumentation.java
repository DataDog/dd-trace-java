package datadog.trace.instrumentation.jakarta.mail;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.EmailInjectionModule;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import java.io.IOException;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public class JakartaMailInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static Logger LOGGER = LoggerFactory.getLogger(JakartaMailInstrumentation.class);

  public JakartaMailInstrumentation() {
    super("jakarta-mail", "jakarta-mail-transport");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("send0"), JakartaMailInstrumentation.class.getName() + "$MailInjectionAdvice");
  }

  @Override
  public String instrumentedType() {
    return "jakarta.mail.Transport";
  }

  public static class MailInjectionAdvice {
    @Sink(VulnerabilityTypes.EMAIL_HTML_INJECTION)
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onSend(@Advice.Argument(0) final Part message)
        throws MessagingException, IOException {
      EmailInjectionModule emailInjectionModule = InstrumentationBridge.EMAIL_INJECTION;
      if (message != null && message.getContent() != null) {
        if (message.isMimeType("text/html")) {
          emailInjectionModule.onSendEmail(message.getContent());
        } else if (message.isMimeType("multipart/*")) {
          Multipart parts = (Multipart) message.getContent();
          for (int i = 0; i < parts.getCount(); i++) {
            if (parts.getBodyPart(i).isMimeType("text/html")) {
              emailInjectionModule.onSendEmail(parts.getBodyPart(i).getContent());
            }
          }
        }
      }
    }
  }
}
