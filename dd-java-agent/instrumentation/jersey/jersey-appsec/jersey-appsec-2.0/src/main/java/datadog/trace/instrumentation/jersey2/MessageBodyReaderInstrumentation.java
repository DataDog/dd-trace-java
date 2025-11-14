package datadog.trace.instrumentation.jersey2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.function.BiFunction;
import javax.ws.rs.core.Form;
import net.bytebuddy.asm.Advice;

// keep in sync with jersey3 (jakarta packages)
@AutoService(InstrumenterModule.class)
public class MessageBodyReaderInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public MessageBodyReaderInstrumentation() {
    super("jersey");
  }

  @Override
  public String muzzleDirective() {
    return "jersey_2";
  }

  // This is a caller for the MessageBodyReaders in jersey
  // We instrument it instead of the MessageBodyReaders in order to avoid hierarchy inspections
  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.message.internal.ReaderInterceptorExecutor";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("proceed").and(takesArguments(0)),
        getClass().getName() + "$ReaderInterceptorExecutorProceedAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class ReaderInterceptorExecutorProceedAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Return final Object ret,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (ret == null || t != null) {
        return;
      }

      if (ret.getClass()
          .getName()
          .equals("org.glassfish.jersey.media.multipart.FormDataMultiPart")) {
        // likely handled already by MultiPartReaderServerSideInstrumentation
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

      Flow<Void> flow = callback.apply(reqCtx, objToPass);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        BlockResponseFunction blockResponseFunction = reqCtx.getBlockResponseFunction();
        if (blockResponseFunction != null) {
          blockResponseFunction.tryCommitBlockingResponse(
              reqCtx.getTraceSegment(),
              rba.getStatusCode(),
              rba.getBlockingContentType(),
              rba.getExtraHeaders());
          t = new BlockingException("Blocked request (for ReaderInterceptorExecutor/proceed)");
          reqCtx.getTraceSegment().effectivelyBlocked();
        }
      }
    }
  }
}
