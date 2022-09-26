package datadog.trace.instrumentation.jersey3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import jakarta.ws.rs.core.Form;
import net.bytebuddy.asm.Advice;

// keep in sync with jersey2 (javax packages)
@AutoService(Instrumenter.class)
public class MessageBodyReaderInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {
  public MessageBodyReaderInstrumentation() {
    super("jersey");
  }

  // This is a caller for the MessageBodyReaders in jersey
  // We instrument it instead of the MessageBodyReaders in order to avoid hierarchy inspections
  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.message.internal.ReaderInterceptorExecutor";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("proceed").and(takesArguments(0)),
        getClass().getName() + "$ReaderInterceptorExecutorProceedAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class ReaderInterceptorExecutorProceedAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.Return final Object ret, @ActiveRequestContext RequestContext reqCtx) {
      if (ret == null) {
        return;
      }

      Object objToPass;
      if (ret instanceof Form) {
        objToPass = ((Form) ret).asMap();
      } else {
        objToPass = ret;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      if (callback == null) {
        return;
      }
      callback.apply(reqCtx, objToPass);
    }
  }
}
