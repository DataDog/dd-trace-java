package datadog.trace.instrumentation.cassandra4;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Collections;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class CassandraClientModule extends InstrumenterModule.Tracing {

  public CassandraClientModule() {
    super("cassandra-toolkit");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CassandraClientDecorator",
      packageName + ".CassandraDBMUtil",
      packageName + ".ContactPointsUtil",
      packageName + ".SpanFinishingCallback",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Collections.singletonList(new CqlSessionExecuteInstrumentation());
  }
}
