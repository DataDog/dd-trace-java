package datadog.trace.instrumentation.grizzly;

import static datadog.trace.instrumentation.grizzly.GrizzlyRequestExtractAdapter.GETTER;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;

public class GrizzlyDecorator extends HttpServerDecorator<Request, Request, Response, Request> {
  public static final CharSequence GRIZZLY = UTF8BytesString.create("grizzly");
  public static final CharSequence GRIZZLY_REQUEST = UTF8BytesString.create("grizzly.request");
  public static final GrizzlyDecorator DECORATE = new GrizzlyDecorator();

  @Override
  protected AgentPropagation.ContextVisitor<Request> getter() {
    return GETTER;
  }

  @Override
  public CharSequence spanName() {
    return GRIZZLY_REQUEST;
  }

  @Override
  protected String method(final Request request) {
    return request.getMethod().getMethodString();
  }

  @Override
  protected URIDataAdapter url(final Request request) {
    return new RequestURIDataAdapter(request);
  }

  @Override
  protected String peerHostIP(final Request request) {
    return request.getRemoteAddr();
  }

  @Override
  protected int peerPort(final Request request) {
    return request.getRemotePort();
  }

  @Override
  protected int status(final Response containerResponse) {
    return containerResponse.getStatus();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"grizzly"};
  }

  @Override
  protected CharSequence component() {
    return GRIZZLY;
  }
}
