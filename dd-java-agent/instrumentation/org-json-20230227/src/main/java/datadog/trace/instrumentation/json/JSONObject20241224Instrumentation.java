package datadog.trace.instrumentation.json;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
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
public class JSONObject20241224Instrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public JSONObject20241224Instrumentation() {
    super("org-json");
  }

  static final ElementMatcher.Junction<ClassLoader> AFTER_20241224 =
      hasClassNamed("org.json.StringBuilderWriter");

  @Override
  public String muzzleDirective() {
    return "after_20241224";
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return AFTER_20241224;
  }

  @Override
  public String instrumentedType() {
    return "org.json.JSONObject";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // public JSONObject(JSONTokener x, JSONParserConfiguration jsonParserConfiguration)
    transformer.applyAdvice(
        isConstructor()
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.json.JSONTokener")))
            .and(takesArgument(1, named("org.json.JSONParserConfiguration"))),
        getClass().getName() + "$ConstructorAdvice");
    // private JSONObject(Map<?, ?> m, int recursionDepth, JSONParserConfiguration
    // jsonParserConfiguration)
    transformer.applyAdvice(
        isConstructor()
            .and(takesArguments(3))
            .and(takesArgument(0, Map.class))
            .and(takesArgument(1, int.class))
            .and(takesArgument(2, named("org.json.JSONParserConfiguration"))),
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
