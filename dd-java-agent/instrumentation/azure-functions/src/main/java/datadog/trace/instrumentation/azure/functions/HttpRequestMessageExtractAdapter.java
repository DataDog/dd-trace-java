package datadog.trace.instrumentation.azure.functions;

import com.microsoft.azure.functions.HttpRequestMessage;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;

public class HttpRequestMessageExtractAdapter
    implements AgentPropagation.ContextVisitor<HttpRequestMessage> {
  public static final HttpRequestMessageExtractAdapter GETTER =
      new HttpRequestMessageExtractAdapter();

  @Override
  public void forEachKey(HttpRequestMessage carrier, AgentPropagation.KeyClassifier classifier) {
    ContextVisitors.stringValuesEntrySet().forEachKey(carrier.getHeaders().entrySet(), classifier);
  }
}
