package datadog.trace.instrumentation.akkahttp;

import akka.http.javadsl.model.HttpHeader;
import akka.http.scaladsl.model.HttpMessage;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;

public class AkkaHttpServerHeaders<T extends HttpMessage>
    implements AgentPropagation.ContextVisitor<T> {

  @SuppressWarnings("rawtypes")
  private static final AkkaHttpServerHeaders GETTER = new AkkaHttpServerHeaders();

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
