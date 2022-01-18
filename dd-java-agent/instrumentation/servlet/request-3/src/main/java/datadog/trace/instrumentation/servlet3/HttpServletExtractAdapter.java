package datadog.trace.instrumentation.servlet3;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Collections;
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
      return request.getHeaderNames();
    }

    @Override
    String getHeader(HttpServletRequest request, String name) {
      return request.getHeader(name);
    }
  }

  public static final class Response extends HttpServletExtractAdapter<HttpServletResponse> {
    public static final Response GETTER = new Response();

    @Override
    Enumeration<String> getHeaderNames(HttpServletResponse response) {
      return Collections.enumeration(response.getHeaderNames());
    }

    @Override
    String getHeader(HttpServletResponse response, String name) {
      return response.getHeader(name);
    }
  }
}
