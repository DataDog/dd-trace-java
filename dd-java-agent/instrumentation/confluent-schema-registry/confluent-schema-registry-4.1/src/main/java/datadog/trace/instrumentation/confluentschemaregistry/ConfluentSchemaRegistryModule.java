package datadog.trace.instrumentation.confluentschemaregistry;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class ConfluentSchemaRegistryModule extends InstrumenterModule.Tracing {
  public ConfluentSchemaRegistryModule() {
    super("confluent-schema-registry", "kafka");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.kafka_common.ClusterIdHolder",
      packageName + ".SchemaIdExtractor"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("org.apache.kafka.common.serialization.Deserializer", "java.lang.Boolean");
    contextStores.put("org.apache.kafka.common.serialization.Serializer", "java.lang.Boolean");
    return contextStores;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(new KafkaDeserializerInstrumentation(), new KafkaSerializerInstrumentation());
  }
}
