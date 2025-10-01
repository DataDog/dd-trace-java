package datadog.trace.instrumentation.servlet2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedNoneOf;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.api.http.StoredByteBody;
import datadog.trace.api.http.StoredCharBody;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.servlet.BufferedReaderWrapper;
import java.io.BufferedReader;
import java.nio.charset.Charset;
import java.util.function.BiFunction;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Request bodies after servlet 3.0.x are covered by Servlet3RequestBodyInstrumentation from the
 * "request-3" module. Any changes to the behaviour here should also be reflected in "request-3".
 */
@AutoService(InstrumenterModule.class)
public class ServletRequestBodyInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public ServletRequestBodyInstrumentation() {
    super("servlet-request-body");
  }

  @Override
  public String muzzleDirective() {
    return "servlet-2.x-and-3.0.x";
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Avoid matching request bodies after 3.0.x which have their own instrumentation
    return not(hasClassNamed("javax.servlet.ReadListener"));
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.http.HttpServletRequest";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        // ignore wrappers that ship with servlet-api
        .and(namedNoneOf("javax.servlet.http.HttpServletRequestWrapper"))
        .and(not(extendsClass(named("javax.servlet.http.HttpServletRequestWrapper"))));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getInputStream")
            .and(takesNoArguments())
            .and(returns(named("javax.servlet.ServletInputStream")))
            .and(isPublic()),
        getClass().getName() + "$HttpServletGetInputStreamAdvice");
    transformer.applyAdvice(
        named("getReader")
            .and(takesNoArguments())
            .and(returns(named("java.io.BufferedReader")))
            .and(isPublic()),
        getClass().getName() + "$HttpServletGetReaderAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.servlet.BufferedReaderWrapper",
      "datadog.trace.instrumentation.servlet.AbstractServletInputStreamWrapper",
      "datadog.trace.instrumentation.servlet2.ServletInputStreamWrapper"
    };
  }

  @Override
  public int order() {
    // apply this instrumentation after the regular servlet one.
    return 1;
  }

  @SuppressWarnings("Duplicates")
  @RequiresRequestContext(RequestContextSlot.APPSEC)
  static class HttpServletGetInputStreamAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.This final HttpServletRequest req,
        @Advice.Return(readOnly = false) ServletInputStream is,
        @ActiveRequestContext RequestContext reqCtx) {
      if (is == null) {
        return;
      }

      Object alreadyWrapped = req.getAttribute("datadog.wrapped_request_body");
      if (alreadyWrapped != null || is instanceof ServletInputStreamWrapper) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, StoredBodySupplier, Void> requestStartCb =
          cbp.getCallback(EVENTS.requestBodyStart());
      BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> requestEndedCb =
          cbp.getCallback(EVENTS.requestBodyDone());
      if (requestStartCb == null || requestEndedCb == null) {
        return;
      }

      req.setAttribute("datadog.wrapped_request_body", Boolean.TRUE);

      int lengthHint = 0;
      String lengthHeader = req.getHeader("content-length");
      if (lengthHeader != null) {
        try {
          lengthHint = Integer.parseInt(lengthHeader);
        } catch (NumberFormatException nfe) {
          // purposefully left blank
        }
      }

      String encoding = req.getCharacterEncoding();
      Charset charset = null;
      try {
        if (encoding != null) {
          charset = Charset.forName(encoding);
        }
      } catch (IllegalArgumentException iae) {
        // purposefully left blank
      }

      StoredByteBody storedByteBody =
          new StoredByteBody(reqCtx, requestStartCb, requestEndedCb, charset, lengthHint);

      is = new ServletInputStreamWrapper(is, storedByteBody);
    }
  }

  @SuppressWarnings("Duplicates")
  @RequiresRequestContext(RequestContextSlot.APPSEC)
  static class HttpServletGetReaderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.This final HttpServletRequest req,
        @Advice.Return(readOnly = false) BufferedReader reader) {
      if (reader == null) {
        return;
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return;
      }
      Object alreadyWrapped = req.getAttribute("datadog.wrapped_request_body");
      if (alreadyWrapped != null || reader instanceof BufferedReaderWrapper) {
        return;
      }
      RequestContext requestContext = agentSpan.getRequestContext();
      if (requestContext == null) {
        return;
      }
      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, StoredBodySupplier, Void> requestStartCb =
          cbp.getCallback(EVENTS.requestBodyStart());
      BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> requestEndedCb =
          cbp.getCallback(EVENTS.requestBodyDone());
      if (requestStartCb == null || requestEndedCb == null) {
        return;
      }

      req.setAttribute("datadog.wrapped_request_body", Boolean.TRUE);

      int lengthHint = 0;
      String lengthHeader = req.getHeader("content-length");
      if (lengthHeader != null) {
        try {
          lengthHint = Integer.parseInt(lengthHeader);
        } catch (NumberFormatException nfe) {
          // purposefully left blank
        }
      }

      StoredCharBody storedCharBody =
          new StoredCharBody(requestContext, requestStartCb, requestEndedCb, lengthHint);

      reader = new BufferedReaderWrapper(reader, storedCharBody);
    }
  }
}
