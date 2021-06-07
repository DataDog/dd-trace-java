package datadog.trace.instrumentation.synapse3;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.http.Header;
import org.apache.http.HeaderIterator;
import org.apache.http.HttpRequest;

public final class HttpRequestExtractAdapter
    implements AgentPropagation.ContextVisitor<HttpRequest> {
  public static final HttpRequestExtractAdapter GETTER = new HttpRequestExtractAdapter();

  @Override
  public void forEachKey(
      final HttpRequest carrier, final AgentPropagation.KeyClassifier classifier) {
    HeaderIterator headerIterator = carrier.headerIterator();
    while (headerIterator.hasNext()) {
      Header header = headerIterator.nextHeader();
      if (!classifier.accept(header.getName(), header.getValue())) {
        break;
      }
    }
  }
}
