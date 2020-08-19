package datadog.trace.instrumentation.grizzly;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.util.MimeHeaders;

public class GrizzlyRequestExtractAdapter implements AgentPropagation.ContextVisitor<Request> {

  public static final GrizzlyRequestExtractAdapter GETTER = new GrizzlyRequestExtractAdapter();

  @Override
  public void forEachKey(Request carrier, AgentPropagation.KeyClassifier classifier) {
    MimeHeaders mimeHeaders = carrier.getRequest().getHeaders();
    for (int i = 0; i < mimeHeaders.size(); ++i) {
      if (!classifier.accept(
          mimeHeaders.getName(i).toString(UTF_8), mimeHeaders.getValue(i).toString(UTF_8))) {
        return;
      }
    }
  }
}
