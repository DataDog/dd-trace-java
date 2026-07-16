package datadog.trace.instrumentation.sparkjava;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Collection;
import java.util.Enumeration;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public abstract class ExtractAdapter {

  public static final class Request
      implements AgentPropagation.ContextVisitor<HttpServletRequest> {
    public static final Request GETTER = new Request();

    @Override
    public void forEachKey(
        HttpServletRequest carrier, AgentPropagation.KeyClassifier classifier) {
      Enumeration<String> headerNames = carrier.getHeaderNames();
      if (headerNames == null) {
        return;
      }
      while (headerNames.hasMoreElements()) {
        String headerName = headerNames.nextElement();
        if (!classifier.accept(headerName, carrier.getHeader(headerName))) {
          return;
        }
      }
    }
  }

  public static final class Response
      implements AgentPropagation.ContextVisitor<HttpServletResponse> {
    public static final Response GETTER = new Response();

    @Override
    public void forEachKey(
        HttpServletResponse carrier, AgentPropagation.KeyClassifier classifier) {
      Collection<String> headerNames = carrier.getHeaderNames();
      if (headerNames == null) {
        return;
      }
      for (String headerName : headerNames) {
        if (!classifier.accept(headerName, carrier.getHeader(headerName))) {
          return;
        }
      }
    }
  }
}
