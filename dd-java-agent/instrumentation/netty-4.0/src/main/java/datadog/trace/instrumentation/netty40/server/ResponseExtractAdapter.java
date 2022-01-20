package datadog.trace.instrumentation.netty40.server;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import io.netty.handler.codec.http.HttpResponse;

public class ResponseExtractAdapter implements AgentPropagation.ContextVisitor<HttpResponse> {
  public static final ResponseExtractAdapter GETTER = new ResponseExtractAdapter();

  @Override
  public void forEachKey(HttpResponse carrier, AgentPropagation.KeyClassifier classifier) {
    ContextVisitors.stringValuesEntrySet().forEachKey(carrier.headers(), classifier);
  }
}
