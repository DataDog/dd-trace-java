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
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Part;
import javax.mail.internet.MimeMultipart;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class JavaxMailInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JavaxMailInstrumentation(String instrumentationName, String... additionalNames) {
    super("javaxmailinstrumentation", "javaxmail");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("javaxMailInstrumentation"),
        JavaxMailInstrumentation.class.getName() + "$MailInjectionAdvice");
  }

  @Override
  public String instrumentedType() {
    return "javax.mail.Transport";
  }

  public static class MailInjectionAdvice {
    @Sink(VulnerabilityTypes.EMAIL_HTML_INJECTION)
    @Advice.OnMethodEnter(suppress = Throwable.class)
    private static void onSend(@Advice.Argument(0) final Message message) {
      EmailInjectionModule emailInjectionModule = InstrumentationBridge.EMAIL_INJECTION;
      if (message != null) {
        try {
          if (message.getContent() != null) {
            if (message.isMimeType("text/html")) { // simple html
              emailInjectionModule.onSendEmail(message.getContent().toString());
            } else if (message.isMimeType(
                "multipart/*")) { // needs to be converted into single string
              if (message.getContent() instanceof MimeMultipart) {
                emailInjectionModule.onSendEmail(
                    convertPartToString((MimeMultipart) message.getContent()));
              }
            }
          }
        } catch (IOException | MessagingException e) {
          throw new RuntimeException(e);
        }
      }
    }

    private static String convertPartToString(MimeMultipart content)
        throws MessagingException, IOException {
      if (content.getCount() == 0) {
        return null;
      }
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < content.getCount(); i++) {
        Part part = content.getBodyPart(i);
        if (part.isMimeType("text/html")) { // only concerned with html injection
          sb.append(part.getContent().toString());
        }
      }
      return sb.toString();
    }
  }
}
