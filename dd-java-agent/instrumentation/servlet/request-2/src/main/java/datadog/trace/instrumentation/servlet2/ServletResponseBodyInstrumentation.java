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
import datadog.trace.instrumentation.servlet.BufferedWriterWrapper;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.function.BiFunction;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Response bodies after servlet 3.0.x are covered by Servlet3ResponseBodyInstrumentation from the
 * "request-3" module. Any changes to the behaviour here should also be reflected in "request-3".
 */
@AutoService(InstrumenterModule.class)
public class ServletResponseBodyInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public ServletResponseBodyInstrumentation() {
    super("servlet-response-body");
  }

  @Override
  public String muzzleDirective() {
    return "servlet-2.x-and-3.0.x";
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Avoid matching response bodies after 3.0.x which have their own instrumentation
    return not(hasClassNamed("javax.servlet.ReadListener"));
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.http.HttpServletResponse";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        // ignore wrappers that ship with servlet-api
        .and(namedNoneOf("javax.servlet.http.HttpServletResponseWrapper"))
        .and(not(extendsClass(named("javax.servlet.http.HttpServletResponseWrapper"))));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getOutputStream")
            .and(takesNoArguments())
            .and(returns(named("javax.servlet.ServletOutputStream")))
            .and(isPublic()),
        getClass().getName() + "$HttpServletGetOutputStreamAdvice");
    transformer.applyAdvice(
        named("getWriter")
            .and(takesNoArguments())
            .and(returns(named("java.io.PrintWriter")))
            .and(isPublic()),
        getClass().getName() + "$HttpServletGetWriterAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.servlet.BufferedWriterWrapper",
      "datadog.trace.instrumentation.servlet.AbstractServletOutputStreamWrapper",
      "datadog.trace.instrumentation.servlet2.ServletOutputStreamWrapper"
    };
  }

  @SuppressWarnings("Duplicates")
  @RequiresRequestContext(RequestContextSlot.APPSEC)
  static class HttpServletGetOutputStreamAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.This final HttpServletResponse resp,
        @Advice.Return(readOnly = false) ServletOutputStream os,
        @ActiveRequestContext RequestContext reqCtx) {
      if (os == null) {
        return;
      }

      if (os instanceof ServletOutputStreamWrapper) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, StoredBodySupplier, Void> responseStartCb =
          cbp.getCallback(EVENTS.responseBodyStart());
      BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> responseEndedCb =
          cbp.getCallback(EVENTS.responseBodyDone());
      if (responseStartCb == null || responseEndedCb == null) {
        return;
      }

      int lengthHint = 0;

      String encoding = resp.getCharacterEncoding();
      Charset charset = null;
      try {
        if (encoding != null) {
          charset = Charset.forName(encoding);
        }
      } catch (IllegalArgumentException iae) {
        // purposefully left blank
      }

      StoredByteBody storedByteBody =
          new StoredByteBody(reqCtx, responseStartCb, responseEndedCb, charset, lengthHint);

      os = new ServletOutputStreamWrapper(os, storedByteBody);
    }
  }

  @SuppressWarnings("Duplicates")
  @RequiresRequestContext(RequestContextSlot.APPSEC)
  static class HttpServletGetWriterAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.This final HttpServletResponse resp,
        @Advice.Return(readOnly = false) PrintWriter writer) {
      if (writer == null) {
        return;
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return;
      }
      if (writer instanceof BufferedWriterWrapper) {
        return;
      }
      RequestContext requestContext = agentSpan.getRequestContext();
      if (requestContext == null) {
        return;
      }
      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, StoredBodySupplier, Void> responseStartCb =
          cbp.getCallback(EVENTS.responseBodyStart());
      BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> responseEndedCb =
          cbp.getCallback(EVENTS.responseBodyDone());
      if (responseStartCb == null || responseEndedCb == null) {
        return;
      }

      int lengthHint = 0;

      StoredCharBody storedCharBody =
          new StoredCharBody(requestContext, responseStartCb, responseEndedCb, lengthHint);

      writer = new BufferedWriterWrapper(writer, storedCharBody);
    }
  }
}
