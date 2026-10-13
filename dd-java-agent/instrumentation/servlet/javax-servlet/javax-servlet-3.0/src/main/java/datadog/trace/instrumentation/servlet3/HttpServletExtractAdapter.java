package datadog.trace.instrumentation.servlet3;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.util.ClassLatch;
import java.util.Collection;
import java.util.Enumeration;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public abstract class HttpServletExtractAdapter<T> implements AgentPropagation.ContextVisitor<T> {

  public static final class Request extends HttpServletExtractAdapter<HttpServletRequest> {
    public static final Request GETTER = new Request();

    @Override
    public void forEachKey(HttpServletRequest carrier, AgentPropagation.KeyClassifier classifier) {
      final Enumeration<String> headerNames = carrier.getHeaderNames();
      if (headerNames == null) {
        return;
      }
      while (headerNames.hasMoreElements()) {
        String header = headerNames.nextElement();
        if (!classifier.accept(header, carrier.getHeader(header))) {
          break;
        }
      }
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

    static final HeaderAccessLatch HEADER_LATCH = new HeaderAccessLatch();

    @Override
    public void forEachKey(HttpServletResponse carrier, AgentPropagation.KeyClassifier classifier) {
      final Collection<String> headerNames = HEADER_LATCH.tryApply(carrier);
      if (headerNames == null) {
        return;
      }
      for (String header : headerNames) {
        // only the accessor call is guarded: an AbstractMethodError from the classifier callback
        // is not evidence that this response class lacks the accessors
        final String value;
        try {
          value = carrier.getHeader(header);
        } catch (AbstractMethodError e) {
          // only partly implemented: latch and stop instead of retrying, which would re-emit keys
          HEADER_LATCH.latchIfLacksGetHeader(carrier, e);
          return;
        }
        if (!classifier.accept(header, value)) {
          return;
        }
      }
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
