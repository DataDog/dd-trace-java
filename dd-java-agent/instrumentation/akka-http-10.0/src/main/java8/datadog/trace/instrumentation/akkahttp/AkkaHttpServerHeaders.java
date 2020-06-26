package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;

import akka.http.javadsl.model.HttpHeader;
import akka.http.scaladsl.model.HttpRequest;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.CachingContextVisitor;

public class AkkaHttpServerHeaders extends CachingContextVisitor<HttpRequest> {

  public static final AkkaHttpServerHeaders GETTER = new AkkaHttpServerHeaders();

  @Override
  public void forEachKey(
      final HttpRequest carrier,
      final AgentPropagation.KeyClassifier classifier,
      final AgentPropagation.KeyValueConsumer consumer) {
    for (final HttpHeader header : carrier.getHeaders()) {
      String lowerCaseKey = toLowerCase(header.name());
      int classification = classifier.classify(lowerCaseKey);
      if (classification != IGNORE) {
        if (!consumer.accept(classification, lowerCaseKey, header.value())) {
          return;
        }
      }
    }
  }
}
