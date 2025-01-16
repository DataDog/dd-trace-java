package datadog.trace.instrumentation.javax.mail;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import javax.mail.Message;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class JavaxMailInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

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

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TransportSendAdvice",
    };
  }

  public static class MailInjectionAdvice {
    @Sink(VulnerabilityTypes.EMAIL_HTML_INJECTION)
    @Advice.OnMethodEnter(suppress = Throwable.class)
    private static void onSend(@Advice.Argument(0) final Message message) {
      TransportSendAdvice.sendAdvice(message);
    }
  }
}
