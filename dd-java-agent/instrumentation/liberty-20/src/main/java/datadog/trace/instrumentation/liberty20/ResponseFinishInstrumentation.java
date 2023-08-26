package datadog.trace.instrumentation.liberty20;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.liberty20.LibertyDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.liberty20.LibertyDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import com.ibm.ws.webcontainer.srt.SRTServletResponse;
import com.ibm.wsspi.webcontainer.WebContainerRequestState;
import com.ibm.wsspi.webcontainer.servlet.IExtendedRequest;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.bytebuddy.asm.Advice;

/**
 * XXX: {@link SRTServletResponse#finish()} is not appropriate method to look at the response
 * headers. By this time, the headers may have been cleared already. They are cleared when the
 * output is closed. There are other problems with this instrumentation. See the comments and NPEs
 * being caught. In short, the method runs too late. Maybe intercept {@link
 * SRTServletResponse#closeResponseOutput()} instead?
 */
@AutoService(Instrumenter.class)
public class ResponseFinishInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public ResponseFinishInstrumentation() {
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
    return "com.ibm.ws.webcontainer.srt.SRTServletResponse";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("finish").and(takesNoArguments()),
        ResponseFinishInstrumentation.class.getName() + "$ResponseFinishAdvice");
    transformation.applyAdvice(
        named("closeResponseOutput").and(takesArguments(1)).and(takesArgument(0, boolean.class)),
        ResponseFinishInstrumentation.class.getName() + "$SetCompletedAdvice");
  }

  /**
   * Older versions have the inserted code conditioned on <code>
   * reqState.getCurrentThreadsIExtendedRequest() == this.getRequest()</code>, though not in all
   * code paths (for instance, not when getOutputStream() was called instead of getWriter). This
   * inconsistency, together with the fact that newer versions don't have this condition anymore,
   * suggests it is a bug. Without this inserted advice, async requests are not completed when
   * blocking async requests.
   */
  static class SetCompletedAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.Argument(0) boolean releaseChannel) {
      WebContainerRequestState reqState = WebContainerRequestState.getInstance(true);
      if (releaseChannel && !reqState.isCompleted()) {
        reqState.setCompleted(true);
      }
    }
  }

  @SuppressFBWarnings("DCN_NULLPOINTER_EXCEPTION")
  public static class ResponseFinishAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static AgentSpan onEnter(@Advice.This SRTServletResponse resp) {
      // this is the last opportunity to have any meaningful
      // interaction with the response
      AgentSpan span = null;
      IExtendedRequest req = resp.getRequest();
      try {
        Object spanObj = req.getAttribute(DD_SPAN_ATTRIBUTE);
        if (spanObj instanceof AgentSpan) {
          span = (AgentSpan) spanObj;
          req.setAttribute(DD_SPAN_ATTRIBUTE, null);
          DECORATE.onResponse(span, resp);
        }
      } catch (NullPointerException e) {
        // OpenLiberty will throw NPE on getAttribute if the response has already been closed.
      }

      return span;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This SRTServletResponse resp, @Advice.Enter AgentSpan span) {
      if (span == null) {
        return;
      }
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
