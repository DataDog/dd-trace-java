package datadog.trace.instrumentation.json;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class JSONObjectBefore20250107Instrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public JSONObjectBefore20250107Instrumentation() {
    super("org-json");
  }

  // Avoid matching servlet 3 which has its own instrumentation
  static final ElementMatcher.Junction<ClassLoader> BEFORE_20250107 =
      not(hasClassNamed("org.json.StringBuilderWriter"));

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return BEFORE_20250107;
  }

  @Override
  public String instrumentedType() {
    return "org.json.JSONObject";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArguments(1)), getClass().getName() + "$ConstructorAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(returns(Object.class))
            .and(named("opt"))
            .and(takesArguments(String.class)),
        packageName + ".OptAdvice");
  }

  public static class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void afterInit(@Advice.This Object self, @Advice.Argument(0) final Object input) {
      final PropagationModule iastModule = InstrumentationBridge.PROPAGATION;
      if (iastModule != null && input != null) {
        iastModule.taintObjectIfTainted(self, input);
      }
    }
  }
}
