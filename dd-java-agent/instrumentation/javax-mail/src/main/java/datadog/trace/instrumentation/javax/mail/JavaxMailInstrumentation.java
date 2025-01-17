package datadog.trace.instrumentation.javax.mail;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.EmailInjectionModule;
import java.io.IOException;
import javax.mail.MessagingException;
import javax.mail.Part;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public class JavaxMailInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static Logger LOGGER = LoggerFactory.getLogger(JavaxMailInstrumentation.class);

  public JavaxMailInstrumentation(String instrumentationName, String... additionalNames) {
    super("javax-mail", "transport");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("send0"), JavaxMailInstrumentation.class.getName() + "$MailInjectionAdvice");
  }

  @Override
  public String instrumentedType() {
    return "javax.mail.Transport";
  }

  public static class MailInjectionAdvice {
    @Sink(VulnerabilityTypes.EMAIL_HTML_INJECTION)
    @Advice.OnMethodEnter(suppress = Throwable.class)
    private static void onSend(@Advice.Argument(0) final Part message)
        throws MessagingException, IOException {
      EmailInjectionModule emailInjectionModule = InstrumentationBridge.EMAIL_INJECTION;
      if (message != null && message.getContent() != null) {
        if (message.isMimeType("text/html")) {
          emailInjectionModule.onSendEmail(message.getContent());
        } else if (message.isMimeType("multipart/*")) {
          Part[] parts = (Part[]) message.getContent();
          for (Part part : parts) {
            if (part.isMimeType("text/html")) {
              emailInjectionModule.onSendEmail(part.getContent());
            }
          }
        }
      }
    }
  }
}
