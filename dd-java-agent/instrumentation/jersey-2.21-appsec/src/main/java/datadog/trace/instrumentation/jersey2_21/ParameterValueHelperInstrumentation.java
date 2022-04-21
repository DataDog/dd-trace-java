package datadog.trace.instrumentation.jersey2_21;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
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
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.glassfish.jersey.server.spi.internal.ParamValueFactoryWithSource;

@AutoService(Instrumenter.class)
public class ParameterValueHelperInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {
  public ParameterValueHelperInstrumentation() {
    super("jersey");
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.server.spi.internal.ParameterValueHelper";
  }

  private static final IReferenceMatcher GET_SOURCE_OLD_REFERENCE_MATCHER =
      new ReferenceMatcher(
          new Reference.Builder(
                  "org.glassfish.jersey.server.spi.internal.ParamValueFactoryWithSource")
              .withMethod(
                  new String[0],
                  Reference.EXPECTS_PUBLIC | Reference.EXPECTS_NON_STATIC,
                  "getSource",
                  "Lorg/glassfish/jersey/server/model/Parameter$Source;")
              .build());

  private static final IReferenceMatcher GET_SOURCE_NEW_REFERENCE_MATCHER =
      new ReferenceMatcher(
          new Reference.Builder(
                  "org.glassfish.jersey.server.spi.internal.ParamValueFactoryWithSource")
              .withMethod(
                  new String[0],
                  Reference.EXPECTS_PUBLIC | Reference.EXPECTS_NON_STATIC,
                  "getSource",
                  "Lorg/glassfish/jersey/model/Parameter$Source;")
              .build());

  private static final IReferenceMatcher EXTRACTOR_REFERENCE_MATCHER =
      new ReferenceMatcher(
          new Reference.Builder(
                  "org.glassfish.jersey.server.internal.inject.MultivaluedParameterExtractor")
              .withMethod(
                  new String[0],
                  Reference.EXPECTS_NON_STATIC | Reference.EXPECTS_PUBLIC,
                  "getName",
                  "Ljava/lang/String;")
              .build());

  private static final IReferenceMatcher EXTRACT_PATH_HELPER_OLD_REFERENCE_MATCHER =
      new IReferenceMatcher.ConjunctionReferenceMatcher(
          new ReferenceMatcher(
              new Reference.Builder(
                      "org.glassfish.jersey.server.spi.internal.ParamValueFactoryWithSource")
                  .withField(
                      new String[0],
                      Reference.EXPECTS_NON_STATIC,
                      "factory",
                      "Lorg/glassfish/hk2/api/Factory;")
                  .build()),
          new ReferenceMatcher(
              new Reference.Builder(
                      "org.glassfish.jersey.server.internal.inject.PathParamValueFactoryProvider$PathParamValueFactory")
                  .withField(
                      new String[0],
                      Reference.EXPECTS_NON_STATIC,
                      "extractor",
                      "Lorg/glassfish/jersey/server/internal/inject/MultivaluedParameterExtractor;")
                  .build()));

  private static final IReferenceMatcher EXTRACT_PATH_HELPER_NEW_REFERENCE_MATCHER =
      new IReferenceMatcher.ConjunctionReferenceMatcher(
          new ReferenceMatcher(
              new Reference.Builder(
                      "org.glassfish.jersey.server.spi.internal.ParamValueFactoryWithSource")
                  .withField(
                      new String[0],
                      Reference.EXPECTS_NON_STATIC,
                      "parameterFunction",
                      "Ljava/util/function/Function;")
                  .build()),
          new ReferenceMatcher(
              new Reference.Builder(
                      "org.glassfish.jersey.server.internal.inject.PathParamValueParamProvider$PathParamListPathSegmentValueSupplier")
                  .withField(
                      new String[0], Reference.EXPECTS_NON_STATIC, "name", "Ljava/lang/String;")
                  .build()));

  private IReferenceMatcher postProcessReferenceMatcher(final ReferenceMatcher origMatcher) {
    return new IReferenceMatcher.ConjunctionReferenceMatcher(
        new IReferenceMatcher.ConjunctionReferenceMatcher(
            new IReferenceMatcher.DisjunctionReferenceMatcher(
                EXTRACT_PATH_HELPER_NEW_REFERENCE_MATCHER,
                EXTRACT_PATH_HELPER_OLD_REFERENCE_MATCHER),
            new IReferenceMatcher.DisjunctionReferenceMatcher(
                GET_SOURCE_NEW_REFERENCE_MATCHER, GET_SOURCE_OLD_REFERENCE_MATCHER)),
        EXTRACTOR_REFERENCE_MATCHER);
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("getParameterValues")
            .and(takesArguments(1).or(takesArguments(2)))
            .and(takesArgument(0, List.class))
            .and(isStatic())
            .and(isPublic()),
        getClass().getName() + "$GetParameterValuesAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".ExtractPathParamsHelper"};
  }

  public static class GetParameterValuesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.Return final Object[] ret,
        @Advice.Argument(0) List<ParamValueFactoryWithSource<?>> paramValues) {
      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
      BiFunction<RequestContext<Object>, Map<String, ?>, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestPathParams());
      RequestContext<Object> requestContext = agentSpan.getRequestContext();
      if (requestContext == null || callback == null) {
        return;
      }

      Map<String, String> map = ExtractPathParamsHelper.buildParamMap(ret, paramValues);
      if (map == null || map.isEmpty()) {
        return;
      }

      callback.apply(requestContext, map);
    }
  }
}
