package datadog.trace.instrumentation.jersey3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import jakarta.ws.rs.core.Form;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class MessageBodyReaderInstrumentation extends Instrumenter.AppSec {
  public MessageBodyReaderInstrumentation() {
    super("jersey");
  }

  // This is a caller for the MessageBodyReaders in jersey
  // We instrument it instead of the MessageBodyReaders in order to avoid hierarchy inspections
  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.glassfish.jersey.message.internal.ReaderInterceptorExecutor");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("proceed").and(takesArguments(0)),
        getClass().getName() + "$ReaderInterceptorExecutorProceedAdvice");
  }

  public static class ReaderInterceptorExecutorProceedAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.Return final Object ret) {
      if (ret == null) {
        return;
      }
      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return;
      }

      Object objToPass;
      if (ret instanceof Form) {
        objToPass = ((Form) ret).asMap();
      } else if (ret.getClass() == String.class || ret instanceof Map || ret instanceof Iterable) {
        objToPass = ret;
      } else {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
      BiFunction<RequestContext<Object>, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      RequestContext<Object> requestContext = agentSpan.getRequestContext();
      if (requestContext == null || callback == null) {
        return;
      }
      callback.apply(requestContext, objToPass);
    }
  }
}
