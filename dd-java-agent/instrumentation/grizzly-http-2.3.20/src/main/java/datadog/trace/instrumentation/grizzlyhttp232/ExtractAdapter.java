package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.nio.charset.StandardCharsets;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.util.MimeHeaders;

public class ExtractAdapter implements AgentPropagation.ContextVisitor<HttpHeader> {
  public static final ExtractAdapter GETTER = new ExtractAdapter();

  @Override
  public void forEachKey(
      HttpHeader carrier,
      AgentPropagation.KeyClassifier classifier,
      AgentPropagation.KeyValueConsumer consumer) {
    // TODO - what about ways to keep the prijection over the bytes a... projection over the bytes?
    MimeHeaders headers = carrier.getHeaders();
    for (int i = 0; i < headers.size(); ++i) {
      String lowerCaseKey = headers.getName(i).toString(StandardCharsets.UTF_8).toLowerCase();
      int classification = classifier.classify(lowerCaseKey);
      if (classification != IGNORE) {
        if (!consumer.accept(
            classification, lowerCaseKey, headers.getValue(i).toString(StandardCharsets.UTF_8))) {
          return;
        }
      }
    }
  }
}
