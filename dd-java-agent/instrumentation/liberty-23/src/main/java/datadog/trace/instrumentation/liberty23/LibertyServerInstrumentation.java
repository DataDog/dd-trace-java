package datadog.trace.instrumentation.liberty23;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.liberty23.HttpInboundServiceContextImplInstrumentation.REQUEST_MSG_TYPE;
import static datadog.trace.instrumentation.liberty23.LibertyDecorator.DD_EXTRACTED_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.liberty23.LibertyDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.liberty23.LibertyDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.ibm.ws.webcontainer.srt.SRTServletRequest;
import com.ibm.ws.webcontainer.srt.SRTServletResponse;
import com.ibm.ws.webcontainer.webapp.WebApp;
import com.ibm.wsspi.webcontainer.webapp.IWebAppDispatcherContext;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.ClassloaderConfigurationOverrides;
import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.servlet5.JakartaServletBlockingHelper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class LibertyServerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

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
      "datadog.trace.instrumentation.servlet5.JakartaServletBlockingHelper",
      packageName + ".RequestMessageFromServletRequestHelper",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        REQUEST_MSG_TYPE, "datadog.trace.bootstrap.instrumentation.api.AgentSpan");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("invokeFilters"))
            .and(takesArgument(0, named("jakarta.servlet.ServletRequest")))
            .and(takesArgument(1, named("jakarta.servlet.ServletResponse")))
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
        @Advice.Local("contextScope") ContextScope scope,
        @Advice.Argument(0) ServletRequest req,
        @Advice.Argument(1) ServletResponse resp) {
      if (!(req instanceof SRTServletRequest)) {
        return false;
      }
      SRTServletRequest request = (SRTServletRequest) req;

      // if we try to get an attribute that doesn't exist open liberty might complain with an
      // exception
      try {
        Object existingSpan = request.getAttribute(DD_SPAN_ATTRIBUTE);
        if (existingSpan instanceof AgentSpan) {
          scope = ((AgentSpan) existingSpan).attach();
          return false;
        }
      } catch (NullPointerException e) {
      }

      final Context context = DECORATE.extract(request);
      request.setAttribute(DD_EXTRACTED_CONTEXT_ATTRIBUTE, context);
      final AgentSpan span = DECORATE.startSpan(request, context);
      scope = context.with(span).attach();
      if (Config.get().isJeeSplitByDeployment()) {
        final IWebAppDispatcherContext dispatcherContext = request.getWebAppDispatcherContext();
        if (dispatcherContext != null) {
          final WebApp webapp = dispatcherContext.getWebApp();
          if (webapp != null) {
            final ClassLoader cl = webapp.getClassLoader();
            if (cl != null) {
              ClassloaderConfigurationOverrides.maybeEnrichSpan(span, cl);
            }
          }
        }
      }
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request, request, context);
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
        JakartaServletBlockingHelper.commitBlockingResponse(
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
        @Advice.Local("contextScope") final ContextScope scope,
        @Advice.Argument(value = 0) ServletRequest req) {
      if (!(req instanceof SRTServletRequest)) {
        return;
      }
      SRTServletRequest request = (SRTServletRequest) req;

      if (scope != null) {
        // we cannot get path at the start because the path/context attributes are not yet
        // initialized
        // this has the unfortunate consequence that service name (as set via the tag interceptor)
        // of the top span won't match that of its child spans, because it's instead the original
        // one that will propagate
        DECORATE.getPath(spanFromContext(scope.context()), request);
        scope.close();
      }
    }
  }
}
