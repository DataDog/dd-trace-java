package datadog.trace.instrumentation.liberty23;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.Enumeration;

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
      return request.getHeaderNames();
    }

    @Override
    String getHeader(HttpServletRequest request, String name) {
      return request.getHeader(name);
    }
  }

  @SuppressFBWarnings("DCN_NULLPOINTER_EXCEPTION")
  public static final class Response extends HttpServletExtractAdapter<HttpServletResponse> {
    public static final Response GETTER = new Response();

    @Override
    Enumeration<String> getHeaderNames(HttpServletResponse response) {
      try {
        return Collections.enumeration(response.getHeaderNames());
      } catch (NullPointerException e) {
        // SRTServletResponse#getHeaderNames() will throw NPE if called after response close.
        return Collections.emptyEnumeration();
      }
    }

    @Override
    String getHeader(HttpServletResponse request, String name) {
      try {
        return request.getHeader(name);
      } catch (NullPointerException e) {
        // SRTServletResponse#getHeader(name) will throw NPE if called after response close.
        return null;
      }
    }
  }
}
