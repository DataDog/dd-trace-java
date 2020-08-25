package datadog.trace.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.Function;
import datadog.trace.bootstrap.instrumentation.cache.DDCache;
import datadog.trace.bootstrap.instrumentation.cache.DDCaches;
import java.util.Enumeration;
import javax.jms.JMSException;
import javax.jms.Message;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MessageExtractAdapter implements AgentPropagation.ContextVisitor<Message> {

  private static final Function<String, String> KEY_MAPPER =
      new Function<String, String>() {
        @Override
        public String apply(String key) {
          return key.replace('$', '-')
              // true story \/
              .replace("__dash__", "-")
              .toLowerCase();
        }
      };

  private final DDCache<String, String> cache = DDCaches.newCache(32);

  public static final MessageExtractAdapter GETTER = new MessageExtractAdapter();

  @Override
  public void forEachKey(Message carrier, AgentPropagation.KeyClassifier classifier) {
    try {
      final Enumeration<?> enumeration = carrier.getPropertyNames();
      if (null != enumeration) {
        while (enumeration.hasMoreElements()) {
          String key = ((String) enumeration.nextElement());
          String lowerCaseKey = cache.computeIfAbsent(key, KEY_MAPPER);
          Object value = carrier.getObjectProperty(key);
          if (value instanceof String && !classifier.accept(lowerCaseKey, (String) value)) {
            return;
          }
        }
      }
    } catch (JMSException e) {
      throw new RuntimeException(e);
    }
  }
}
