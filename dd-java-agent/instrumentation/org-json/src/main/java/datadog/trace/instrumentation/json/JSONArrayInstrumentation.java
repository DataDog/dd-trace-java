package datadog.trace.instrumentation.json;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;
import org.json.JSONArray;
import org.json.JSONObject;

@AutoService(InstrumenterModule.class)
public class JSONArrayInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType {

  public JSONArrayInstrumentation() {
    super("org-json");
  }

  @Override
  public String instrumentedType() {
    return "org.json.JSONArray";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArguments(String.class)),
        getClass().getName() + "$ConstructorAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(returns(Object.class))
            .and(named("get"))
            .and(takesArguments(1)),
        getClass().getName() + "$GetAdvice");
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(returns(Object.class)).and(named("opt")),
        getClass().getName() + "$OptAdvice");
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

  public static class GetAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void afterMethod(@Advice.This Object self, @Advice.Return final Object result) {
      boolean isString = result instanceof String;
      boolean isJson = !isString && (result instanceof JSONObject || result instanceof JSONArray);
      if (!isString && !isJson) {
        return;
      }
      final PropagationModule iastModule = InstrumentationBridge.PROPAGATION;
      if (iastModule != null) {
        if (isString) {
          iastModule.taintStringIfTainted((String) result, self);
        } else {
          iastModule.taintObjectIfTainted(result, self);
        }
      }
    }
  }

  public static class OptAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void afterMethod(@Advice.This Object self, @Advice.Return final Object result) {
      boolean isString = result instanceof String;
      boolean isJson = !isString && (result instanceof JSONObject || result instanceof JSONArray);
      if (!isString && !isJson) {
        return;
      }
      final PropagationModule iastModule = InstrumentationBridge.PROPAGATION;
      if (iastModule != null) {
        if (isString) {
          iastModule.taintStringIfTainted((String) result, self);
        } else {
          iastModule.taintObjectIfTainted(result, self);
        }
      }
    }
  }
}
