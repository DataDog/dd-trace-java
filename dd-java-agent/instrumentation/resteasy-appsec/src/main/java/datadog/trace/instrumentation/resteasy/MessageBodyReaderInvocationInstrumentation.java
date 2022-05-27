package datadog.trace.instrumentation.resteasy;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
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
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class MessageBodyReaderInvocationInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForKnownTypes {

  public MessageBodyReaderInvocationInstrumentation() {
    super("resteasy");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"org.jboss.resteasy.core.interception.AbstractReaderInterceptorContext"};
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("readFrom")
            .and(takesArguments(1))
            .and(takesArgument(0, nameEndsWith(".MessageBodyReader"))),
        MessageBodyReaderInvocationInstrumentation.class.getName()
            + "$AbstractReaderInterceptorAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class AbstractReaderInterceptorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.Return final Object ret, @ActiveRequestContext RequestContext reqCtx) {
      if (ret == null) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      if (callback == null) {
        return;
      }
      callback.apply(reqCtx, ret);
    }
  }
}
