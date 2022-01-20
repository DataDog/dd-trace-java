package datadog.trace.instrumentation.spray;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import scala.collection.JavaConverters;
import spray.http.HttpHeader;
import spray.http.HttpRequest;
import spray.http.HttpResponse;

public abstract class SprayHeaders<T> implements AgentPropagation.ContextVisitor<T> {

  protected abstract Iterable<HttpHeader> getHeaders(T carrier);

  @Override
  public void forEachKey(T carrier, AgentPropagation.KeyClassifier classifier) {
    for (final HttpHeader header : getHeaders(carrier)) {
      if (!classifier.accept(header.lowercaseName(), header.value())) {
        return;
      }
    }
  }

  public static final class Request extends SprayHeaders<HttpRequest> {
    public static final Request GETTER = new Request();

    @Override
    protected Iterable<HttpHeader> getHeaders(HttpRequest carrier) {
      return JavaConverters.asJavaIterableConverter(carrier.headers()).asJava();
    }
  }

  public static final class Response extends SprayHeaders<HttpResponse> {
    public static final Response GETTER = new Response();

    @Override
    protected Iterable<HttpHeader> getHeaders(HttpResponse carrier) {
      return JavaConverters.asJavaIterableConverter(carrier.headers()).asJava();
    }
  }
}
