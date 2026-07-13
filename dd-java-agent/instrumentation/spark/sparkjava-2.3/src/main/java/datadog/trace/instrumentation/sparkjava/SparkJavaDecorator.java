package datadog.trace.instrumentation.sparkjava;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class SparkJavaDecorator
    extends HttpServerDecorator<
        HttpServletRequest, HttpServletRequest, HttpServletResponse, HttpServletRequest> {
  public static final CharSequence SPARK_JAVA = UTF8BytesString.create("spark-java");
  public static final CharSequence SPARK_REQUEST = UTF8BytesString.create("spark.request");
  public static final SparkJavaDecorator DECORATE = new SparkJavaDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"sparkjava"};
  }

  @Override
  protected CharSequence component() {
    return SPARK_JAVA;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpServletRequest> getter() {
    return ExtractAdapter.Request.GETTER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpServletResponse> responseGetter() {
    return ExtractAdapter.Response.GETTER;
  }

  @Override
  public CharSequence spanName() {
    return SPARK_REQUEST;
  }

  @Override
  protected String method(final HttpServletRequest request) {
    return request.getMethod();
  }

  @Override
  protected URIDataAdapter url(final HttpServletRequest request) {
    return new RequestURIDataAdapter(request);
  }

  @Override
  protected String peerHostIP(final HttpServletRequest request) {
    return request.getRemoteAddr();
  }

  @Override
  protected int peerPort(final HttpServletRequest request) {
    return request.getRemotePort();
  }

  @Override
  protected int status(final HttpServletResponse response) {
    return response.getStatus();
  }
}
