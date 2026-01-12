package datadog.trace.instrumentation.liberty23;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.liberty23.LibertyDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import com.ibm.ws.webcontainer.srt.SRTServletRequest;
import com.ibm.ws.webcontainer.srt.SRTServletResponse;
import com.ibm.wsspi.webcontainer.servlet.IExtendedResponse;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class RequestFinishInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public RequestFinishInstrumentation() {
    super("liberty");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HttpServletExtractAdapter",
      packageName + ".HttpServletExtractAdapter$Request",
      packageName + ".HttpServletExtractAdapter$Response",
      packageName + ".LibertyDecorator",
      packageName + ".LibertyDecorator$LibertyBlockResponseFunction",
      packageName + ".RequestURIDataAdapter",
    };
  }

  @Override
  public String instrumentedType() {
    return "com.ibm.ws.webcontainer.srt.SRTServletRequest";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("finish").and(takesNoArguments()),
        RequestFinishInstrumentation.class.getName() + "$RequestFinishAdvice");
  }

  /** The function finish is called when a server receives and sends out a request */
  @SuppressFBWarnings("DCN_NULLPOINTER_EXCEPTION")
  public static class RequestFinishAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(@Advice.This SRTServletRequest req) {
      IExtendedResponse resp = req.getResponse();

      // this should be a servlet response
      if (resp instanceof SRTServletResponse) {
        SRTServletResponse httpResp = (SRTServletResponse) resp;
        Object contextObj = null;
        try {
          contextObj = req.getAttribute(DD_CONTEXT_ATTRIBUTE);
        } catch (NullPointerException e) {
          // OpenLiberty will throw NPE on getAttribute if the response has already been closed.
        }

        if (contextObj instanceof Context) {
          req.setAttribute(DD_CONTEXT_ATTRIBUTE, null);
          final Context context = (Context) contextObj;
          final AgentSpan span = fromContext(context);
          if (span != null) {
            DECORATE.onResponse(span, httpResp);
            DECORATE.beforeFinish(context);
            span.finish();
          }
        }
      }
    }
  }
}
