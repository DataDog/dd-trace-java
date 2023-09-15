package datadog.trace.instrumentation.liberty20;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.liberty20.HttpInboundServiceContextImplInstrumentation.REQUEST_MSG_TYPE;
import static datadog.trace.instrumentation.liberty20.LibertyDecorator.DD_EXTRACTED_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.liberty20.LibertyDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.liberty20.LibertyDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.ibm.ws.webcontainer.srt.SRTServletRequest;
import com.ibm.ws.webcontainer.srt.SRTServletResponse;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.servlet.ServletBlockingHelper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class LibertyServerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public LibertyServerInstrumentation() {
    super("liberty");
  }

  @Override
  public String instrumentedType() {
    return "com.ibm.ws.webcontainer.filter.WebAppFilterManager";
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
      "datadog.trace.instrumentation.servlet.ServletBlockingHelper",
      packageName + ".RequestMessageFromServletRequestHelper",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        REQUEST_MSG_TYPE, "datadog.trace.bootstrap.instrumentation.api.AgentSpan");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("invokeFilters"))
            .and(takesArgument(0, named("javax.servlet.ServletRequest")))
            .and(takesArgument(1, named("javax.servlet.ServletResponse")))
            .and(takesArgument(2, named("com.ibm.wsspi.webcontainer.servlet.IServletContext")))
            .and(takesArgument(3, named("com.ibm.wsspi.webcontainer.RequestProcessor")))
            .and(takesArgument(4, EnumSet.class))
            .and(takesArgument(5, named("com.ibm.wsspi.http.HttpInboundConnection"))),
        LibertyServerInstrumentation.class.getName() + "$HandleRequestAdvice");
  }

  @SuppressFBWarnings("DCN_NULLPOINTER_EXCEPTION")
  public static class HandleRequestAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    public static boolean /* skip */ onEnter(
        @Advice.Local("agentScope") AgentScope scope,
        @Advice.Argument(0) ServletRequest req,
        @Advice.Argument(1) ServletResponse resp) {
      if (!(req instanceof SRTServletRequest)) return false;
      SRTServletRequest request = (SRTServletRequest) req;

      // if we try to get an attribute that doesn't exist open liberty might complain with an
      // exception
      try {
        Object existingSpan = request.getAttribute(DD_SPAN_ATTRIBUTE);
        if (existingSpan instanceof AgentSpan) {
          scope = activateSpan((AgentSpan) existingSpan);
          return false;
        }
      } catch (NullPointerException e) {
      }

      final AgentSpan.Context.Extracted extractedContext = DECORATE.extract(request);
      request.setAttribute(DD_EXTRACTED_CONTEXT_ATTRIBUTE, extractedContext);
      final AgentSpan span = DECORATE.startSpan(request, extractedContext);
      scope = activateSpan(span);
      scope.setAsyncPropagation(true);

      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request, request, extractedContext);
      request.setAttribute(DD_SPAN_ATTRIBUTE, span);
      request.setAttribute(CorrelationIdentifier.getTraceIdKey(), GlobalTracer.get().getTraceId());
      request.setAttribute(CorrelationIdentifier.getSpanIdKey(), GlobalTracer.get().getSpanId());

      if (ActiveSubsystems.APPSEC_ACTIVE) {
        ContextStore store =
            InstrumentationContext.get(
                REQUEST_MSG_TYPE, "datadog.trace.bootstrap.instrumentation.api.AgentSpan");
        // Provide the span to lower layers
        // The span is associated with the c.i.w.http.channel.internal.HttpRequestMessageImpl object
        store.put(RequestMessageFromServletRequestHelper.getHttpRequestMessage(request), span);
      }

      Flow.Action.RequestBlockingAction rba = span.getRequestBlockingAction();
      if (rba != null) {
        ServletBlockingHelper.commitBlockingResponse(
            span.getRequestContext().getTraceSegment(), request, (SRTServletResponse) resp, rba);
        // prevent caching of the handler
        req.setAttribute(
            "javax.servlet.error.status_code", ((SRTServletResponse) resp).getStatusCode());
        span.getRequestContext().getTraceSegment().effectivelyBlocked();
        return true; // skip method body
      }

      return false;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void closeScope(
        @Advice.Local("agentScope") final AgentScope scope,
        @Advice.Argument(value = 0) ServletRequest req) {
      if (!(req instanceof SRTServletRequest)) return;
      SRTServletRequest request = (SRTServletRequest) req;

      if (scope != null) {
        // we cannot get path at the start because the path/context attributes are not yet
        // initialized
        // this has the unfortunate consequence that service name (as set via the tag interceptor)
        // of the top span won't match that of its child spans, because it's instead the original
        // one that will propagate
        DECORATE.getPath(scope.span(), request);
        scope.close();
      }
    }
  }
}
