package datadog.trace.instrumentation.servlet3;

import static java.util.Collections.emptyEnumeration;
import static java.util.Collections.enumeration;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.util.ClassLatch;
import java.util.Collection;
import java.util.Enumeration;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public abstract class HttpServletExtractAdapter<T> implements AgentPropagation.ContextVisitor<T> {
  abstract Enumeration<String> getHeaderNames(T t);

  abstract String getHeader(T t, String name);

  @Override
  public void forEachKey(T carrier, AgentPropagation.KeyClassifier classifier) {
    Enumeration<String> headerNames = getHeaderNames(carrier);
    while (headerNames.hasMoreElements()) {
      String header = headerNames.nextElement();
      if (!classifier.accept(header, getHeader(carrier, header))) {
        break;
      }
    }
  }

  public static final class Request extends HttpServletExtractAdapter<HttpServletRequest> {
    public static final Request GETTER = new Request();

    @Override
    Enumeration<String> getHeaderNames(HttpServletRequest request) {
      final Enumeration<String> ret = request.getHeaderNames();
      return ret != null ? ret : emptyEnumeration();
    }

    @Override
    String getHeader(HttpServletRequest request, String name) {
      return request.getHeader(name);
    }
  }

  /**
   * Reads response headers through the Servlet 3.0 accessors. A response class compiled against
   * Servlet 2.5 that implements {@link HttpServletResponse} itself does not implement them, so
   * calling them throws {@link AbstractMethodError}. Such classes are latched and visit no headers.
   * Subclasses of {@code HttpServletResponseWrapper} are not affected: they inherit the container's
   * delegating implementations.
   */
  public static final class Response extends HttpServletExtractAdapter<HttpServletResponse> {
    public static final Response GETTER = new Response();

    static final HeaderAccessLatch HEADER_ACCESS = new HeaderAccessLatch();

    @Override
    public void forEachKey(HttpServletResponse carrier, AgentPropagation.KeyClassifier classifier) {
      final Collection<String> headerNames = HEADER_ACCESS.tryApply(carrier);
      if (headerNames == null) {
        return;
      }
      for (String header : headerNames) {
        // only the accessor call is guarded: an AbstractMethodError from the classifier callback
        // is not evidence that this response class lacks the accessors
        final String value;
        try {
          value = getHeader(carrier, header);
        } catch (AbstractMethodError e) {
          // only partly implemented: latch and stop instead of retrying, which would re-emit keys
          HEADER_ACCESS.latchIfLacksGetHeader(carrier, e);
          return;
        }
        if (!classifier.accept(header, value)) {
          return;
        }
      }
    }

    @Override
    Enumeration<String> getHeaderNames(HttpServletResponse response) {
      final Collection<String> headerNames = response.getHeaderNames();
      return headerNames != null ? enumeration(headerNames) : emptyEnumeration();
    }

    @Override
    String getHeader(HttpServletResponse response, String name) {
      return response.getHeader(name);
    }

    /** Skips response classes that do not implement the Servlet 3.0 header accessors. */
    static final class HeaderAccessLatch
        extends ClassLatch<HttpServletResponse, Collection<String>, RuntimeException> {
      @Override
      protected Collection<String> apply(HttpServletResponse response) {
        return handleAbstractMethod(
            response, "getHeaderNames", HttpServletResponse::getHeaderNames);
      }

      void latchIfLacksGetHeader(HttpServletResponse response, AbstractMethodError error) {
        latchIfNamed(response, "getHeader", error);
      }
    }
  }
}
