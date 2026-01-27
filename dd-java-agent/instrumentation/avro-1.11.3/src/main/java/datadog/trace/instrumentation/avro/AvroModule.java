package datadog.trace.instrumentation.avro;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class AvroModule extends InstrumenterModule.Tracing {
  public AvroModule() {
    super("avro");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SchemaExtractor", packageName + ".SchemaExtractor$1",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(new GenericDatumReaderInstrumentation(), new GenericDatumWriterInstrumentation());
  }
}
