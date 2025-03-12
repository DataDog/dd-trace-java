package datadog.trace.instrumentation.json;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class JSONCookieInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JSONCookieInstrumentation() {
    super("org-json");
  }

  @Override
  public String muzzleDirective() {
    return "all";
  }

  @Override
  public String instrumentedType() {
    return "org.json.Cookie";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("toJSONObject").and(takesArguments(String.class)),
        getClass().getName() + "$toJSONObjectAdvice");
  }

  public static class toJSONObjectAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void onExit(
        @Advice.Return Object retValue, @Advice.Argument(0) final String input) {
      final PropagationModule iastModule = InstrumentationBridge.PROPAGATION;
      if (iastModule != null && input != null) {
        iastModule.taintObjectIfTainted(retValue, input);
      }
    }
  }
}
