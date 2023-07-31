package datadog.trace.instrumentation.springmessaging;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.springframework.messaging.Message;

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
    for (Map.Entry<String, ?> header : carrier.getHeaders().entrySet()) {
      if (header.getValue() instanceof String) {
        String lowerCaseKey = cache.computeIfAbsent(header.getKey(), KEY_MAPPER);
        if (!classifier.accept(lowerCaseKey, (String) header.getValue())) {
          return;
        }
      }
    }
  }
}
