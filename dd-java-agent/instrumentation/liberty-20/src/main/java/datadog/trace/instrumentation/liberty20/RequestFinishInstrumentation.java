package datadog.trace.instrumentation.liberty20;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.liberty20.LibertyDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.liberty20.LibertyDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import com.ibm.ws.webcontainer.srt.SRTServletRequest;
import com.ibm.ws.webcontainer.srt.SRTServletResponse;
import com.ibm.wsspi.webcontainer.servlet.IExtendedResponse;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class RequestFinishInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
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
        Object spanObj = null;
        try {
          spanObj = req.getAttribute(DD_SPAN_ATTRIBUTE);
        } catch (NullPointerException e) {
          // OpenLiberty will throw NPE on getAttribute if the response has already been closed.
        }

        if (spanObj instanceof AgentSpan) {
          req.setAttribute(DD_SPAN_ATTRIBUTE, null);
          final AgentSpan span = (AgentSpan) spanObj;
          DECORATE.onResponse(span, httpResp);
          DECORATE.beforeFinish(span);
          span.finish();
        }
      }
    }
  }
}
