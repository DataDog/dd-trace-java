package datadog.trace.instrumentation.jetty92;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.util.MultiMap;

@AutoService(Instrumenter.class)
public class RequestExtractContentParametersInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {
  private static final String MULTI_MAP_INTERNAL_NAME = "Lorg/eclipse/jetty/util/MultiMap;";

  public RequestExtractContentParametersInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.Request";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("extractContentParameters").and(takesArguments(0)),
        getClass().getName() + "$ExtractContentParametersAdvice");
  }

  private static final Reference REQUEST_REFERENCE =
      new Reference.Builder("org.eclipse.jetty.server.Request")
          .withMethod(new String[0], 0, "extractContentParameters", MULTI_MAP_INTERNAL_NAME)
          .withField(new String[0], 0, "_contentParameters", MULTI_MAP_INTERNAL_NAME)
          .build();

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {REQUEST_REFERENCE};
  }

  public static class ExtractContentParametersAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.Return MultiMap<String> map) {
      if (map == null || map.isEmpty()) {
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
      callback.apply(requestContext, map);
    }
  }
}
