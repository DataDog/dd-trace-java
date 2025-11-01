package datadog.trace.instrumentation.liberty20;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.liberty20.LibertyDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import com.ibm.ws.webcontainer.srt.SRTServletResponse;
import com.ibm.wsspi.webcontainer.WebContainerRequestState;
import com.ibm.wsspi.webcontainer.servlet.IExtendedRequest;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
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
@AutoService(InstrumenterModule.class)
public class ResponseFinishInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

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
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("finish").and(takesNoArguments()),
        ResponseFinishInstrumentation.class.getName() + "$ResponseFinishAdvice");
    transformer.applyAdvice(
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
    static Context onEnter(@Advice.This SRTServletResponse resp) {
      // this is the last opportunity to have any meaningful
      // interaction with the response
      Context context = null;
      IExtendedRequest req = resp.getRequest();
      try {
        Object contextObj = req.getAttribute(DD_CONTEXT_ATTRIBUTE);
        if (contextObj instanceof Context) {
          context = (Context) contextObj;
          req.setAttribute(DD_CONTEXT_ATTRIBUTE, null);
          AgentSpan span = fromContext(context);
          if (span != null) {
            DECORATE.onResponse(span, resp);
          }
        }
      } catch (NullPointerException e) {
        // OpenLiberty will throw NPE on getAttribute if the response has already been closed.
      }

      return context;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This SRTServletResponse resp, @Advice.Enter Context context) {
      if (context == null) {
        return;
      }
      AgentSpan span = fromContext(context);
      if (span != null) {
        DECORATE.beforeFinish(context);
        span.finish();
      }
    }
  }
}
