package datadog.trace.instrumentation.spray;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import scala.collection.JavaConverters;
import spray.http.HttpHeader;
import spray.http.HttpRequest;

public final class SprayHeaders implements AgentPropagation.ContextVisitor<HttpRequest> {

  public static final SprayHeaders GETTER = new SprayHeaders();

  @Override
  public void forEachKey(HttpRequest carrier, AgentPropagation.KeyClassifier classifier) {
    for (final HttpHeader header :
        JavaConverters.asJavaIterableConverter(carrier.headers()).asJava()) {
      if (!classifier.accept(header.lowercaseName(), header.value())) {
        return;
      }
    }
  }
}
