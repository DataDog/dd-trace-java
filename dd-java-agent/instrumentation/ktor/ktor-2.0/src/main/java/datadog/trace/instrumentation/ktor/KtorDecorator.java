package datadog.trace.instrumentation.ktor;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import io.ktor.http.HttpStatusCode;
import io.ktor.server.application.ApplicationCall;
import io.ktor.server.request.ApplicationRequest;
import io.ktor.server.response.ApplicationResponse;

public class KtorDecorator
    extends HttpServerDecorator<
        ApplicationRequest, ApplicationRequest, ApplicationResponse, ApplicationCall> {

  public static final CharSequence KTOR_REQUEST =
      UTF8BytesString.create("ktor.request"); // TODO what are the naming rules for the span name?
  public static final CharSequence COMPONENT =
      UTF8BytesString.create(
          "ktor"); // TODO should it be a more general v1 naming name in the beginning?

  public static final KtorDecorator DECORATE = new KtorDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"ktor", "ktor-2.0"};
  }

  @Override
  protected CharSequence component() {
    // TODO is it okay that we override netty/jetty/tomcat component here?
    //  returning null doesn't prevent from removing the original one
    return COMPONENT;
  }

  @Override
  protected AgentPropagation.ContextVisitor<ApplicationCall> getter() {
    // TODO
    return null;
  }

  @Override
  protected AgentPropagation.ContextVisitor<ApplicationResponse> responseGetter() {
    // TODO
    return null;
  }

  @Override
  public CharSequence spanName() {
    return KTOR_REQUEST;
  }

  @Override
  protected String method(ApplicationRequest request) {
    return request.getLocal().getMethod().getValue();
  }

  @Override
  protected URIDataAdapter url(ApplicationRequest request) {
    return new RequestURIDataAdapter(request);
  }

  @Override
  protected String peerHostIP(ApplicationRequest applicationCall) {
    // TODO
    return null;
  }

  @Override
  protected int peerPort(ApplicationRequest request) {
    // TODO
    return 0;
  }

  @Override
  protected int status(ApplicationResponse applicationCall) {
    HttpStatusCode status = applicationCall.status();
    if (status != null) {
      return status.getValue();
    }
    return 0;
  }

  @Override
  public CharSequence operationName() {
    return KTOR_REQUEST;
  }
}
