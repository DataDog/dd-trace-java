package datadog.trace.instrumentation.servlet.http;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.api.http.StoredByteBody;
import datadog.trace.api.http.StoredCharBody;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.BufferedReader;
import java.nio.charset.Charset;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class ServletRequestBodyInstrumentation extends Instrumenter.AppSec {
  public ServletRequestBodyInstrumentation() {
    super("servlet-request-body");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("javax.servlet.http.HttpServlet");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return implementsInterface(named("javax.servlet.ServletRequest"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("getInputStream")
            .and(takesNoArguments())
            .and(returns(named("javax.servlet.ServletInputStream")))
            .and(isPublic()),
        getClass().getName() + "$HttpServletGetInputStreamAdvice");
    transformation.applyAdvice(
        named("getReader")
            .and(takesNoArguments())
            .and(returns(named("java.io.BufferedReader")))
            .and(isPublic()),
        getClass().getName() + "$HttpServletGetReaderAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.servlet.http.ServletInputStreamWrapper",
      "datadog.trace.instrumentation.servlet.http.BufferedReaderWrapper",
    };
  }

  @SuppressWarnings("Duplicates")
  static class HttpServletGetInputStreamAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.This final ServletRequest thiz,
        @Advice.Return(readOnly = false) ServletInputStream is) {
      if (!(thiz instanceof HttpServletRequest) || is == null) {
        return;
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return;
      }
      HttpServletRequest req = (HttpServletRequest) thiz;
      Object alreadyWrapped = req.getAttribute("datadog.wrapped_request_body");
      if (alreadyWrapped != null || is instanceof ServletInputStreamWrapper) {
        return;
      }
      RequestContext requestContext = agentSpan.getRequestContext();
      if (requestContext == null) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
      BiFunction<RequestContext, StoredBodySupplier, Void> requestStartCb =
          cbp.getCallback(Events.REQUEST_BODY_START);
      BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> requestEndedCb =
          cbp.getCallback(Events.REQUEST_BODY_DONE);
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
          new StoredByteBody(requestContext, requestStartCb, requestEndedCb, charset, lengthHint);
      ServletInputStreamWrapper servletInputStreamWrapper =
          new ServletInputStreamWrapper(is, storedByteBody);

      is = servletInputStreamWrapper;
    }
  }

  @SuppressWarnings("Duplicates")
  static class HttpServletGetReaderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.This final ServletRequest thiz,
        @Advice.Return(readOnly = false) BufferedReader reader) {
      if (!(thiz instanceof HttpServletRequest) || reader == null) {
        return;
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return;
      }
      HttpServletRequest req = (HttpServletRequest) thiz;
      Object alreadyWrapped = req.getAttribute("datadog.wrapped_request_body");
      if (alreadyWrapped != null || reader instanceof BufferedReaderWrapper) {
        return;
      }
      RequestContext requestContext = agentSpan.getRequestContext();
      if (requestContext == null) {
        return;
      }
      CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
      BiFunction<RequestContext, StoredBodySupplier, Void> requestStartCb =
          cbp.getCallback(Events.REQUEST_BODY_START);
      BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> requestEndedCb =
          cbp.getCallback(Events.REQUEST_BODY_DONE);
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
