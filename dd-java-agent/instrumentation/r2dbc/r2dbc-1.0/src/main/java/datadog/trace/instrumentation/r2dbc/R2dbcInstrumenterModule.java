package datadog.trace.instrumentation.r2dbc;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class R2dbcInstrumenterModule extends InstrumenterModule.Tracing {

  public R2dbcInstrumenterModule() {
    super("r2dbc");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".R2dbcConnectionInfo",
      packageName + ".R2dbcDecorator",
      packageName + ".R2dbcSQLCommenter",
      packageName + ".TracingPublisher",
      packageName + ".TracingPublisher$TracingSubscriber",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>();
    contextStore.put("io.r2dbc.spi.Statement", packageName + ".R2dbcConnectionInfo");
    return contextStore;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    List<Instrumenter> instrumenters = new ArrayList<>(3);
    instrumenters.add(new ConnectionInstrumentation());
    instrumenters.add(new StatementInstrumentation());
    instrumenters.add(new BatchInstrumentation());
    return instrumenters;
  }
}
