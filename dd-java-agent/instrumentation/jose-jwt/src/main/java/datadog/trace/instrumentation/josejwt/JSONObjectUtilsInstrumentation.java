package datadog.trace.instrumentation.josejwt;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class JSONObjectUtilsInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public JSONObjectUtilsInstrumentation() {
    super("jwt", "jose-jwt");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("parse").and(isPublic().and(takesArguments(String.class))),
        JSONObjectUtilsInstrumentation.class.getName() + "$InstrumenterAdvice");
  }

  @Override
  public String instrumentedType() {
    return "com.nimbusds.jose.util.JSONObjectUtils";
  }

  public static class InstrumenterAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void onEnter(@Advice.Return Map<String, Object> map) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;

      if (module != null) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
          final String name = entry.getKey();
          final Object value = entry.getValue();
          if (value instanceof String) {
            // TODO: We could represent this source more accurately, perhaps tracking the original
            // source, or using a special name.
            module.taint(value, SourceTypes.REQUEST_HEADER_VALUE, name);
          }
        }
      }
    }
  }
}
