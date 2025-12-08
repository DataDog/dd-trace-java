package datadog.trace.instrumentation.kafka_clients38;

import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProducerConstructorAdvice {
  private static final Logger log = LoggerFactory.getLogger(ProducerConstructorAdvice.class);

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void captureConfiguration(
      @Advice.Argument(0) ProducerConfig producerConfig) {
    logProducerConfiguration(producerConfig);
  }

  private static void logProducerConfiguration(ProducerConfig producerConfig) {
    try {
      log.info("Kafka Producer started");
      log.info("Producer Configuration (all properties):");
      
      // Get all configuration values
      java.util.Map<String, ?> allConfigs = producerConfig.values();
      
      // Sort by key for consistent output
      allConfigs.entrySet().stream()
          .sorted(java.util.Map.Entry.comparingByKey())
          .forEach(entry -> {
            log.info("  {}: {}", entry.getKey(), entry.getValue());
          });
      
      // TODO: Add data capture logic here
    } catch (Exception e) {
      log.debug("Error logging producer configuration", e);
    }
  }
}

