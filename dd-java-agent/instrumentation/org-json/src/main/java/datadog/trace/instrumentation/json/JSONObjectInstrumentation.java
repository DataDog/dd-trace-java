package datadog.trace.instrumentation.json;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class JSONObjectInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {
  public JSONObjectInstrumentation() {
    super("org-json");
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
            .and(named("get"))
            .and(takesArguments(String.class)),
        getClass().getName() + "$GetAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(returns(Object.class))
            .and(named("opt"))
            .and(takesArguments(String.class)),
        getClass().getName() + "$OptAdvice");
  }

  public static class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void afterInit(@Advice.This Object self, @Advice.Argument(0) final Object input) {
      final PropagationModule iastModule = InstrumentationBridge.PROPAGATION;
      if (iastModule != null && input != null) {
        iastModule.taintIfTainted(self, input);
      }
    }
  }

  public static class GetAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void afterMethod(@Advice.This Object self, @Advice.Return final Object result) {
      if (result instanceof Integer
          || result instanceof Long
          || result instanceof Double
          || result instanceof Boolean) {
        return;
      }
      final PropagationModule iastModule = InstrumentationBridge.PROPAGATION;
      if (iastModule != null && result != null) {
        iastModule.taintIfTainted(result, self);
      }
    }
  }

  public static class OptAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void afterMethod(@Advice.This Object self, @Advice.Return final Object result) {
      if (result instanceof Integer
          || result instanceof Long
          || result instanceof Double
          || result instanceof Boolean) {
        return;
      }
      final PropagationModule iastModule = InstrumentationBridge.PROPAGATION;
      if (iastModule != null && result != null) {
        iastModule.taintIfTainted(result, self);
      }
    }
  }
}
