package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.grizzlyhttp232.GrizzlyDecorator.onHttpServerFilterPrepareResponseEnter;
import static datadog.trace.instrumentation.grizzlyhttp232.GrizzlyDecorator.onHttpServerFilterPrepareResponseExit;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.util.HttpStatus;

public class HttpServerFilterAdvice {

  /**
   * @see org.glassfish.grizzly.http.HttpServerFilter#prepareResponse(FilterChainContext,
   *     HttpRequestPacket, HttpResponsePacket, HttpContent)
   */
  @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
  public static boolean /* skip */ onEnter(
      @Advice.Local("isContinue") boolean isContinue,
      @Advice.Argument(0) final FilterChainContext ctx,
      @Advice.Argument(1) final HttpRequestPacket request,
      @Advice.Argument(2) final HttpResponsePacket response) {
    if (response.getHttpStatus() == HttpStatus.CONINTUE_100) {
      isContinue = true;
      return false;
    }

    Flow.Action.RequestBlockingAction rba = onHttpServerFilterPrepareResponseEnter(ctx, response);
    if (rba != null) {
      AgentSpan span = (AgentSpan) ctx.getAttributes().getAttribute(DD_SPAN_ATTRIBUTE);
      RequestContext requestContext = span.getRequestContext();
      BlockResponseFunction brf = requestContext.getBlockResponseFunction();
      if (brf != null) {
        boolean success =
            GrizzlyHttpBlockingHelper.block(
                ctx,
                request.getHeader("accept"),
                rba.getStatusCode(),
                rba.getBlockingContentType(),
                rba.getExtraHeaders());
        if (success) {
          requestContext.getTraceSegment().effectivelyBlocked();
          return true; // skip body
        }
      }
    }
    return false;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Local("isContinue") boolean isContinue,
      @Advice.Enter boolean skipped,
      @Advice.Argument(0) final FilterChainContext ctx,
      @Advice.Argument(2) final HttpResponsePacket response,
      @Advice.Thrown(readOnly = false) Throwable thrown) {
    if (isContinue) {
      return;
    }

    onHttpServerFilterPrepareResponseExit(ctx, response);
    if (skipped) {
      thrown =
          new BlockingException(
              "Blocked by replacing response (for HttpServerFilter/prepareResponse)");
    }
  }
}
