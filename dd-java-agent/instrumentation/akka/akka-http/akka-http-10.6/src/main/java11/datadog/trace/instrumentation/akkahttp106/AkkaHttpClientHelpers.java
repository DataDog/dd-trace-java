package datadog.trace.instrumentation.akkahttp106;

import akka.http.javadsl.model.headers.RawHeader;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.http.scaladsl.model.headers.CustomHeader;
import datadog.context.propagation.CarrierSetter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import scala.runtime.AbstractFunction1;
import scala.util.Try;

public final class AkkaHttpClientHelpers {
  public static class OnCompleteHandler extends AbstractFunction1<Try<HttpResponse>, Void> {
    private final AgentSpan span;

    public OnCompleteHandler(final AgentSpan span) {
      this.span = span;
    }

    @Override
    public Void apply(final Try<HttpResponse> result) {
      if (result.isSuccess()) {
        AkkaHttpClientDecorator.DECORATE.onResponse(span, result.get());
      } else {
        AkkaHttpClientDecorator.DECORATE.onError(span, result.failed().get());
      }
      AkkaHttpClientDecorator.DECORATE.beforeFinish(span);
      span.finish();
      return null;
    }
  }

  public static class AkkaHttpHeaders implements CarrierSetter<HttpRequest> {
    private HttpRequest request;
    // Did this request have a span when the AkkaHttpHeaders object was created?
    private boolean hadSpan;

    public AkkaHttpHeaders(final HttpRequest request) {
      hadSpan = request != null && request.getHeader("x-datadog-request-has-span").isPresent();
      if (hadSpan || request == null) {
        this.request = request;
      } else {
        this.request = (HttpRequest) request.addHeader(new HasSpanHeader());
      }
    }

    public boolean hadSpan() {
      return hadSpan;
    }

    @Override
    public void set(final HttpRequest carrier, final String key, final String value) {
      request = (HttpRequest) request.addHeader(RawHeader.create(key, value));
    }

    public HttpRequest getRequest() {
      return request;
    }
  }

  // Custom header to mark that this request has a span associated with it
  public static class HasSpanHeader extends CustomHeader {
    @Override
    public String name() {
      return "x-datadog-request-has-span";
    }

    @Override
    public String value() {
      return "true";
    }

    @Override
    public boolean renderInRequests() {
      return false;
    }

    @Override
    public boolean renderInResponses() {
      return false;
    }
  }
}
