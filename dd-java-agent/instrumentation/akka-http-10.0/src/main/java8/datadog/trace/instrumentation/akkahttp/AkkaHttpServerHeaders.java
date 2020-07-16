package datadog.trace.instrumentation.akkahttp;

import akka.http.javadsl.model.HttpHeader;
import akka.http.scaladsl.model.HttpRequest;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;

public class AkkaHttpServerHeaders implements AgentPropagation.ContextVisitor<HttpRequest> {

  public static final AkkaHttpServerHeaders GETTER = new AkkaHttpServerHeaders();

  @Override
  public void forEachKey(
      final HttpRequest carrier, final AgentPropagation.KeyClassifier classifier) {
    for (final HttpHeader header : carrier.getHeaders()) {
      if (!classifier.accept(header.lowercaseName(), header.value())) {
        return;
      }
    }
  }
}
