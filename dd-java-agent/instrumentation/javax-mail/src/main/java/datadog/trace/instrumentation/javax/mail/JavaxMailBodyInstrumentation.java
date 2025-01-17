package datadog.trace.instrumentation.javax.mail;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.EmailInjectionModule;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class JavaxMailBodyInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JavaxMailBodyInstrumentation(String instrumentationName, String... additionalNames) {
    super("javax-mail", "body");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("setContent"),
        JavaxMailBodyInstrumentation.class.getName() + "$ContentInjectionAdvice");
    transformer.applyAdvice(
        named("setText"), JavaxMailBodyInstrumentation.class.getName() + "$TextInjectionAdvice");
  }

  @Override
  public String instrumentedType() {
    return "javax.mail.Part";
  }

  public static class ContentInjectionAdvice {
    @Sink(VulnerabilityTypes.EMAIL_HTML_INJECTION)
    @Advice.OnMethodEnter(suppress = Throwable.class)
    private static void onSetContent(@Advice.Argument(0) final Object content) {
      EmailInjectionModule emailInjectionModule = InstrumentationBridge.EMAIL_INJECTION;
      if (content != null) {
        emailInjectionModule.taint(content);
      }
    }
  }

  public static class TextInjectionAdvice {
    @Sink(VulnerabilityTypes.EMAIL_HTML_INJECTION)
    @Advice.OnMethodEnter(suppress = Throwable.class)
    private static void onSetText(@Advice.Argument(0) final String text) {
      EmailInjectionModule emailInjectionModule = InstrumentationBridge.EMAIL_INJECTION;
      if (text != null) {
        emailInjectionModule.taint(text);
      }
    }
  }
}
