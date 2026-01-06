package datadog.trace.instrumentation.jms;

import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_BATCH_ID_KEY;
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_PRODUCED_KEY;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Enumeration;
import java.util.Locale;
import java.util.function.Function;
import javax.jms.JMSException;
import javax.jms.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MessageExtractAdapter implements AgentPropagation.ContextVisitor<Message> {
  private static final Logger log = LoggerFactory.getLogger(MessageExtractAdapter.class);
  private static final Function<String, String> KEY_MAPPER =
      new Function<String, String>() {
        @SuppressForbidden
        @Override
        public String apply(String key) {
          return key.replace("__dash__", "-").replace('$', '-').toLowerCase(Locale.ROOT);
        }
      };

  private final DDCache<String, String> cache = DDCaches.newFixedSizeCache(32);

  public static final MessageExtractAdapter GETTER = new MessageExtractAdapter();

  @Override
  public void forEachKey(Message carrier, AgentPropagation.KeyClassifier classifier) {
    try {
      final Enumeration<?> enumeration = carrier.getPropertyNames();
      if (null != enumeration) {
        while (enumeration.hasMoreElements()) {
          String key = ((String) enumeration.nextElement());
          String lowerCaseKey = cache.computeIfAbsent(key, KEY_MAPPER);
          Object value = null;
          try {
            value = carrier.getObjectProperty(key);
          } catch (Throwable t) {
            // log and ignore if we cannot access this property but don't break the instrumentation
            if (log.isDebugEnabled()) {
              log.debug("Error accessing message property {}", key, t);
            }
          }
          if (value instanceof String && !classifier.accept(lowerCaseKey, (String) value)) {
            return;
          }
        }
      }
    } catch (JMSException e) {
      throw new RuntimeException(e);
    }
  }

  public long extractTimeInQueueStart(final Message carrier) {
    try {
      if (carrier.propertyExists(JMS_PRODUCED_KEY)) {
        return carrier.getLongProperty(JMS_PRODUCED_KEY);
      }
    } catch (Exception e) {
      log.debug("Unable to get jms produced time", e);
    }
    return 0;
  }

  public long extractMessageBatchId(final Message carrier) {
    try {
      if (carrier.propertyExists(JMS_BATCH_ID_KEY)) {
        return carrier.getLongProperty(JMS_BATCH_ID_KEY);
      }
    } catch (Exception e) {
      log.debug("Unable to get jms batch id", e);
    }
    return 0;
  }
}
