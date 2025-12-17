package datadog.trace.instrumentation.grizzlyhttp232;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.nio.charset.StandardCharsets;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.util.MimeHeaders;

public class ExtractAdapter<T extends HttpHeader> implements AgentPropagation.ContextVisitor<T> {

  @SuppressWarnings("rawtypes")
  private static final ExtractAdapter GETTER = new ExtractAdapter();

  private ExtractAdapter() {}

  @SuppressWarnings("unchecked")
  public static AgentPropagation.ContextVisitor<HttpRequestPacket> requestGetter() {
    return (AgentPropagation.ContextVisitor<HttpRequestPacket>) GETTER;
  }

  @SuppressWarnings("unchecked")
  public static AgentPropagation.ContextVisitor<HttpResponsePacket> responseGetter() {
    return (AgentPropagation.ContextVisitor<HttpResponsePacket>) GETTER;
  }

  @Override
  public void forEachKey(T carrier, AgentPropagation.KeyClassifier classifier) {
    MimeHeaders headers = carrier.getHeaders();
    if (headers == null) {
      return;
    }
    for (int i = 0; i < headers.size(); ++i) {
      if (!classifier.accept(
          headers.getName(i).toString(StandardCharsets.UTF_8),
          headers.getValue(i).toString(StandardCharsets.UTF_8))) {
        return;
      }
    }
  }
}
