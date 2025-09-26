package datadog.trace.instrumentation.pekkohttp;

import static datadog.trace.instrumentation.pekkohttp.PekkoHttpClientDecorator.DECORATE;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.annotation.ParametersAreNonnullByDefault;
import org.apache.pekko.http.javadsl.model.headers.RawHeader;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.HttpResponse;
import org.apache.pekko.http.scaladsl.model.headers.CustomHeader;
import scala.runtime.AbstractFunction1;
import scala.util.Try;

public final class PekkoHttpClientHelpers {
  public static class OnCompleteHandler extends AbstractFunction1<Try<HttpResponse>, Void> {
    private final AgentSpan span;

    public OnCompleteHandler(final AgentSpan span) {
      this.span = span;
    }

    @Override
    public Void apply(final Try<HttpResponse> result) {
      if (result.isSuccess()) {
        DECORATE.onResponse(span, result.get());
      } else {
        DECORATE.onError(span, result.failed().get());
      }
      DECORATE.beforeFinish(span);
      span.finish();
      return null;
    }
  }

  public static class PekkoHttpHeaders implements CarrierSetter<HttpRequest> {
    private HttpRequest request;
    // Did this request have a span when the PekkoHttpHeaders object was created?
    private final boolean hadSpan;

    public PekkoHttpHeaders(final HttpRequest request) {
      hadSpan = request != null && request.getHeader(HasSpanHeader.class).isPresent();
      if (hadSpan || request == null) {
        this.request = request;
      } else {
        // Coerce a Scala trait Self type into the correct type
        this.request = (HttpRequest) request.addHeader(new HasSpanHeader());
      }
    }

    public boolean hadSpan() {
      return hadSpan;
    }

    @ParametersAreNonnullByDefault
    @Override
    public void set(final HttpRequest carrier, final String key, final String value) {
      // Coerce a Scala trait Self type into the correct type
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
