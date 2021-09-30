package datadog.trace.instrumentation.grizzlyhttp232;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.nio.charset.StandardCharsets;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.util.MimeHeaders;

public class ExtractAdapter implements AgentPropagation.ContextVisitor<HttpRequestPacket> {
  public static final ExtractAdapter GETTER = new ExtractAdapter();

  @Override
  public void forEachKey(HttpRequestPacket carrier, AgentPropagation.KeyClassifier classifier) {
    MimeHeaders headers = carrier.getHeaders();
    for (int i = 0; i < headers.size(); ++i) {
      if (!classifier.accept(
          headers.getName(i).toString(StandardCharsets.UTF_8),
          headers.getValue(i).toString(StandardCharsets.UTF_8))) {
        return;
      }
    }
  }
}
