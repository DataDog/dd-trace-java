package datadog.trace.instrumentation.springmessaging;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.messaging.DatadogAttributeParser;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;

public final class SpringMessageExtractAdapter
    implements AgentPropagation.ContextVisitor<Message<?>> {

  private static final Function<String, String> KEY_MAPPER =
      new Function<String, String>() {
        @SuppressForbidden
        @Override
        public String apply(String key) {
          // normalize headers from different providers; raw SQS, JMS, spring-messaging, etc.
          if ("AWSTraceHeader".equals(key) || "Sqs_Msa_AWSTraceHeader".equals(key)) {
            return "x-amzn-trace-id";
          }
          return key.replace("__dash__", "-").replace('$', '-').toLowerCase(Locale.ROOT);
        }
      };

  private final DDCache<String, String> cache = DDCaches.newFixedSizeCache(32);

  public static final SpringMessageExtractAdapter GETTER = new SpringMessageExtractAdapter();

  @Override
  public void forEachKey(Message<?> carrier, AgentPropagation.KeyClassifier classifier) {
    final MessageHeaders messageHeaders = carrier.getHeaders();
    if (messageHeaders == null || messageHeaders.isEmpty()) {
      return;
    }

    for (Map.Entry<String, ?> header : messageHeaders.entrySet()) {
      Object headerValue = header.getValue();
      if ("_datadog".equals(header.getKey())) {
        if (headerValue instanceof String) {
          DatadogAttributeParser.forEachProperty(classifier, (String) headerValue);
        } else if (headerValue instanceof ByteBuffer) {
          DatadogAttributeParser.forEachProperty(classifier, (ByteBuffer) headerValue);
        }
      } else if (headerValue instanceof String) {
        String lowerCaseKey = cache.computeIfAbsent(header.getKey(), KEY_MAPPER);
        if (!classifier.accept(lowerCaseKey, (String) headerValue)) {
          return;
        }
      }
    }
  }
}
