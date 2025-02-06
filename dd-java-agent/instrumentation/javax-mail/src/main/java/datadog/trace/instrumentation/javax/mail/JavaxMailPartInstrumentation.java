package datadog.trace.instrumentation.javax.mail;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import javax.mail.Part;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class JavaxMailPartInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public JavaxMailPartInstrumentation() {
    super("javax-mail", "javax-mail-body");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("setContent").and(takesArgument(0, Object.class)),
        JavaxMailPartInstrumentation.class.getName() + "$ContentInjectionAdvice");
    transformer.applyAdvice(
        named("setText").and(takesArgument(0, String.class)),
        JavaxMailPartInstrumentation.class.getName() + "$TextInjectionAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.mail.Part";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
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
