package datadog.trace.instrumentation.javax.mail;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
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
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public class JavaxMailInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private static Logger LOGGER = LoggerFactory.getLogger(JavaxMailInstrumentation.class);

  public JavaxMailInstrumentation(String instrumentationName, String... additionalNames) {
    super("javax-mail", "transport");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("send"), JavaxMailInstrumentation.class.getName() + "$MailInjectionAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.mail.Transport";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  public static class MailInjectionAdvice {
    @Sink(VulnerabilityTypes.EMAIL_HTML_INJECTION)
    @Advice.OnMethodEnter(suppress = Throwable.class)
    private static void onSend(@Advice.Argument(0) final Part message) {
      EmailInjectionModule emailInjectionModule = InstrumentationBridge.EMAIL_INJECTION;
      try {
        if (message != null && message.getContent() != null) {
          // content can be set via Object(setContent) or String(setText) so we need to check both
          // the Object and String
          // content ends up being set in BodyPart.setContent(Object) and BodyPart.setText(String)
        }
      } catch (IOException | MessagingException e) {
        LOGGER.debug("Failed to get content from message", e);
      }
    }
  }
}
