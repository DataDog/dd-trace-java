package datadog.trace.instrumentation.finatra;

import com.twitter.finagle.http.Request;
import com.twitter.finagle.http.Response;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;

public class FinatraDecorator extends HttpServerDecorator<Request, Request, Response, Void> {

  public static final CharSequence FINATRA = UTF8BytesString.create("finatra");
  public static final CharSequence FINATRA_CONTROLLER =
      UTF8BytesString.create("finatra.controller");
  public static final FinatraDecorator DECORATE = new FinatraDecorator();

  private static final CharSequence FINATRA_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected CharSequence component() {
    return FINATRA;
  }

  @Override
  protected AgentPropagation.ContextVisitor<Void> getter() {
    return null;
  }

  @Override
  protected AgentPropagation.ContextVisitor<Response> responseGetter() {
    return null;
  }

  @Override
  public CharSequence spanName() {
    return FINATRA_REQUEST;
  }

  @Override
  protected String method(final Request request) {
    return request.method().name();
  }

  @Override
  protected URIDataAdapter url(final Request request) {
    return URIDataAdapterBase.fromURI(request.uri(), URIDefaultDataAdapter::new);
  }

  @Override
  protected String peerHostIP(final Request request) {
    return request.remoteAddress().getHostAddress();
  }

  @Override
  protected int peerPort(final Request request) {
    return request.remotePort();
  }

  @Override
  protected int status(final Response response) {
    return response.statusCode();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"finatra"};
  }
}
