package datadog.trace.instrumentation.pekkohttp;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.pekko.http.javadsl.model.HttpHeader;
import org.apache.pekko.http.scaladsl.model.HttpMessage;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.HttpResponse;

public class PekkoHttpServerHeaders<T extends HttpMessage>
    implements AgentPropagation.ContextVisitor<T> {

  @SuppressWarnings("rawtypes")
  private static final PekkoHttpServerHeaders GETTER = new PekkoHttpServerHeaders();

  private PekkoHttpServerHeaders() {}

  @SuppressWarnings("unchecked")
  public static AgentPropagation.ContextVisitor<HttpRequest> requestGetter() {
    return (AgentPropagation.ContextVisitor<HttpRequest>) GETTER;
  }

  @SuppressWarnings("unchecked")
  public static AgentPropagation.ContextVisitor<HttpResponse> responseGetter() {
    return (AgentPropagation.ContextVisitor<HttpResponse>) GETTER;
  }

  @Override
  public void forEachKey(
      final HttpMessage carrier, final AgentPropagation.KeyClassifier classifier) {
    for (final HttpHeader header : carrier.getHeaders()) {
      if (!classifier.accept(header.lowercaseName(), header.value())) {
        return;
      }
    }
  }
}
