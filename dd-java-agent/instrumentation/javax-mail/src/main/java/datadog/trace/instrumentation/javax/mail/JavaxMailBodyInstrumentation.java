package datadog.trace.instrumentation.javax.mail;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import javax.mail.Part;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class JavaxMailBodyInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JavaxMailBodyInstrumentation() {
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
    @Propagation
    @Advice.OnMethodEnter(suppress = Throwable.class)
    private static void onSetContent(
        @Advice.This Part part, @Advice.Argument(0) final Object content) {
      PropagationModule propagationModule = InstrumentationBridge.PROPAGATION;
      if (propagationModule != null && content != null) {
        propagationModule.taintObjectIfTainted(part, content);
      }
    }
  }

  public static class TextInjectionAdvice {
    @Propagation
    @Advice.OnMethodEnter(suppress = Throwable.class)
    private static void onSetText(@Advice.This Part part, @Advice.Argument(0) final String text) {
      PropagationModule propagationModule = InstrumentationBridge.PROPAGATION;
      if (propagationModule != null && text != null) {
        propagationModule.taintObjectIfTainted(part, text);
      }
    }
  }
}
