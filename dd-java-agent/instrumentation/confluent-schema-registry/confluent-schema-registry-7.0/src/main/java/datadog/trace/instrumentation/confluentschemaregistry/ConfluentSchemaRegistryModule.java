package datadog.trace.instrumentation.confluentschemaregistry;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instrumentation module for Confluent Schema Registry to capture schema operations including
 * registration, compatibility checks, serialization, and deserialization.
 */
@AutoService(InstrumenterModule.class)
public class ConfluentSchemaRegistryModule extends InstrumenterModule.Tracing {

  public ConfluentSchemaRegistryModule() {
    super("confluent-schema-registry");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SchemaRegistryMetrics", packageName + ".SchemaRegistryContext",
    };
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<Instrumenter> typeInstrumentations() {
    return (List<Instrumenter>)
        (List<?>)
            asList(
                new SchemaRegistryClientInstrumentation(),
                new KafkaAvroSerializerInstrumentation(),
                new KafkaAvroDeserializerInstrumentation());
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("io.confluent.kafka.schemaregistry.client.SchemaRegistryClient");
  }
}
