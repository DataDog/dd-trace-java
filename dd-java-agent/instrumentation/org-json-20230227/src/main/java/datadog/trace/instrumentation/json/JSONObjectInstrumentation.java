package datadog.trace.instrumentation.json;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class JSONObjectInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public JSONObjectInstrumentation() {
    super("org-json");
  }

  static final ElementMatcher.Junction<ClassLoader> BEFORE_20241224 =
      not(hasClassNamed("org.json.StringBuilderWriter"));

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return BEFORE_20241224;
  }

  @Override
  public String muzzleDirective() {
    return "before_20241224";
  }

  @Override
  public String instrumentedType() {
    return "org.json.JSONObject";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // public JSONObject(JSONTokener x)
    transformer.applyAdvice(
        isConstructor().and(takesArguments(1)).and(takesArgument(0, named("org.json.JSONTokener"))),
        getClass().getName() + "$ConstructorAdvice");
    // private JSONObject(Map<?, ?> m)
    transformer.applyAdvice(
        isConstructor().and(takesArguments(1)).and(takesArgument(0, Map.class)),
        getClass().getName() + "$ConstructorAdvice");
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
