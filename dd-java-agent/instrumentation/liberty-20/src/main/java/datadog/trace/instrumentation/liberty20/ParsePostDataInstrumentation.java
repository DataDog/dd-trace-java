package datadog.trace.instrumentation.liberty20;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Hashtable;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class ParsePostDataInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForKnownTypes {
  public ParsePostDataInstrumentation() {
    super("liberty");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "com.ibm.ws.webcontainer.srt.SRTServletRequest",
      "com.ibm.ws.webcontainer31.srt.SRTServletRequest31",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("parsePostData"))
            .and(isPublic().or(isProtected()))
            .and(takesArguments(0))
            .and(returns(Hashtable.class)),
        ParsePostDataInstrumentation.class.getName() + "$ParsePostDataAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  static class ParsePostDataAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.Return Hashtable<String, String[]> retval,
        @ActiveRequestContext RequestContext reqCtx) {
      if (retval == null || retval.isEmpty()) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      if (callback == null) {
        return;
      }
      callback.apply(reqCtx, retval);
    }
  }
}
