package datadog.trace.instrumentation.tomcat6;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.*;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.IReferenceMatcher;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.ByteCodeElement;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.tomcat.util.http.Parameters;

@AutoService(Instrumenter.class)
public class ParsedBodyParametersInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.WithTypeStructure {

  public ParsedBodyParametersInstrumentation() {
    super("tomcat");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.tomcat.util.http.Parameters";
  }

  @Override
  public ElementMatcher<? extends ByteCodeElement> structureMatcher() {
    return declaresField(named("paramHashValues"));
  }

  // paramHashValues was also of type Hashtable, but only for 4 days between
  // commits c1c2e29d55ea41d76ab4bf688dbaafb9b100eadf and 211c381310db7ded0c7e1a1ef11dd4f62e7c71bb
  private static final ReferenceMatcher PARAM_HASH_VALUES_MAP_REFERENCE_MATCHER =
      new ReferenceMatcher(
          new Reference.Builder("org.apache.tomcat.util.http.Parameters")
              .withField(new String[0], 0, "paramHashValues", "Ljava/util/Map;")
              .build());

  private static final ReferenceMatcher PARAM_HASH_VALUES_HASH_MAP_REFERENCE_MATCHER =
      new ReferenceMatcher(
          new Reference.Builder("org.apache.tomcat.util.http.Parameters")
              .withField(new String[0], 0, "paramHashValues", "Ljava/util/HashMap;")
              .build());

  private IReferenceMatcher postProcessReferenceMatcher(final ReferenceMatcher origMatcher) {
    return new IReferenceMatcher() {
      @Override
      public boolean matches(ClassLoader loader) {
        return origMatcher.matches(loader)
            && (PARAM_HASH_VALUES_MAP_REFERENCE_MATCHER.matches(loader)
                || PARAM_HASH_VALUES_MAP_REFERENCE_MATCHER.matches(loader));
      }

      @Override
      public List<Reference.Mismatch> getMismatchedReferenceSources(ClassLoader loader) {
        List<Reference.Mismatch> allMismatches =
            new ArrayList<>(origMatcher.getMismatchedReferenceSources(loader));
        List<Reference.Mismatch> mismatchesMap =
            PARAM_HASH_VALUES_MAP_REFERENCE_MATCHER.getMismatchedReferenceSources(loader);
        List<Reference.Mismatch> mismatchesHashMap =
            PARAM_HASH_VALUES_HASH_MAP_REFERENCE_MATCHER.getMismatchedReferenceSources(loader);
        if (!mismatchesHashMap.isEmpty() && !mismatchesMap.isEmpty()) {
          allMismatches.addAll(mismatchesHashMap);
          allMismatches.addAll(mismatchesMap);
        }
        return allMismatches;
      }
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        // also matches the variant taking an extra encoding parameter
        named("processParameters")
            .and(takesArgument(0, byte[].class))
            .and(takesArgument(1, int.class))
            .and(takesArgument(2, int.class)),
        getClass().getName() + "$ProcessParametersAdvice");

    transformation.applyAdvice(
        named("handleQueryParameters").and(takesArguments(0)),
        getClass().getName() + "$HandleQueryParametersAdvice");
  }

  // skip advice in processParameters if we're inside handleQueryParameters()
  public static class HandleQueryParametersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static int before() {
      return CallDepthThreadLocalMap.incrementCallDepth(Parameters.class);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(@Advice.Enter final int depth) {
      if (depth > 0) {
        return;
      }
      CallDepthThreadLocalMap.reset(Parameters.class);
    }
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

        CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
        BiFunction<RequestContext<Object>, Object, Flow<Void>> callback =
            cbp.getCallback(EVENTS.requestBodyProcessed());
        RequestContext<Object> requestContext = agentSpan.getRequestContext();
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
