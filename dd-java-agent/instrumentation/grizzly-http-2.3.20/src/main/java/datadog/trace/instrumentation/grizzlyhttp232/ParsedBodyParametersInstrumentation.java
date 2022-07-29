package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.http.util.Parameters;

// tested in GlassFishServerTest
@AutoService(Instrumenter.class)
public class ParsedBodyParametersInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {

  public ParsedBodyParametersInstrumentation() {
    super("grizzly");
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.grizzly.http.util.Parameters";
  }

  private static final Reference PARAM_HASH_VALUES_HASH_MAP_REFERENCE =
      new Reference.Builder("org.glassfish.grizzly.http.util.Parameters")
          .withField(new String[0], 0, "paramHashValues", "Ljava/util/LinkedHashMap;")
          .build();

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {PARAM_HASH_VALUES_HASH_MAP_REFERENCE};
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        // also matches the variant taking an extra encoding parameter
        named("processParameters")
            .and(takesArgument(0, named("org.glassfish.grizzly.Buffer")))
            .and(takesArgument(1, int.class))
            .and(takesArgument(2, int.class))
            .and(takesArgument(3, Charset.class)),
        getClass().getName() + "$ProcessParametersAdvice");
  }

  @SuppressWarnings("Duplicates")
  public static class ProcessParametersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static int before(
        @Advice.FieldValue(value = "paramHashValues")
            final Map<String, ArrayList<String>> paramValuesField,
        @Advice.Local("origParamHashValues") Map<String, ArrayList<String>> origParamValues) {
      int depth = CallDepthThreadLocalMap.incrementCallDepth(Parameters.class);
      if (depth == 0 && !paramValuesField.isEmpty()) {
        // field is final
        origParamValues = new HashMap<>(paramValuesField);
        paramValuesField.clear();
      }
      return depth;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Local("origParamHashValues") Map<String, ArrayList<String>> origParamValues,
        @Advice.FieldValue("paramHashValues") final Map<String, ArrayList<String>> paramValuesField,
        @Advice.Enter final int depth) {
      if (depth > 0) {
        return;
      }
      CallDepthThreadLocalMap.reset(Parameters.class);

      try {
        if (paramValuesField.isEmpty()) {
          return;
        }

        AgentSpan agentSpan = activeSpan();
        if (agentSpan == null) {
          return;
        }

        CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
        BiFunction<RequestContext, Object, Flow<Void>> callback =
            cbp.getCallback(EVENTS.requestBodyProcessed());
        RequestContext requestContext = agentSpan.getRequestContext();
        if (requestContext == null || callback == null) {
          return;
        }
        callback.apply(requestContext, paramValuesField);
      } finally {
        if (origParamValues != null) {
          paramValuesField.putAll(origParamValues);
        }
      }
    }
  }
}
