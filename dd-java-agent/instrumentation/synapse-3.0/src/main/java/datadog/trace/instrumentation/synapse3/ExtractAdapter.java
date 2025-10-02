package datadog.trace.instrumentation.synapse3;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.http.Header;
import org.apache.http.HeaderIterator;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;

public abstract class ExtractAdapter<T> implements AgentPropagation.ContextVisitor<T> {

  abstract HeaderIterator getHeaders(T carrier);

  @Override
  public void forEachKey(T carrier, AgentPropagation.KeyClassifier classifier) {
    final HeaderIterator headerIterator = getHeaders(carrier);
    while (headerIterator != null && headerIterator.hasNext()) {
      Header header = headerIterator.nextHeader();
      if (!classifier.accept(header.getName(), header.getValue())) {
        break;
      }
    }
  }

  public static final class Request extends ExtractAdapter<HttpRequest> {
    public static final Request GETTER = new Request();

    @Override
    HeaderIterator getHeaders(HttpRequest carrier) {
      return carrier != null ? carrier.headerIterator() : null;
    }
  }

  public static final class Response extends ExtractAdapter<HttpResponse> {
    public static final Response GETTER = new Response();

    @Override
    HeaderIterator getHeaders(HttpResponse carrier) {
      return carrier != null ? carrier.headerIterator() : null;
    }
  }
}
