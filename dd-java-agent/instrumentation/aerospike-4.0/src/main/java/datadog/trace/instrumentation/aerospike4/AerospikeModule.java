package datadog.trace.instrumentation.aerospike4;

import static java.util.Collections.singleton;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import java.util.ArrayList;
import java.util.List;

@AutoService(InstrumenterModule.class)
public final class AerospikeModule extends InstrumenterModule {
  public AerospikeModule() {
    super("aerospike");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AerospikeClientDecorator", packageName + ".TracingListener",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    final List<Instrumenter> ret = new ArrayList<>(4);
    ret.add(new AerospikeClientInstrumentation());
    ret.add(new CommandInstrumentation());
    if (InstrumenterConfig.get().isIntegrationEnabled(singleton("java_concurrent"), true)) {
      ret.add(new NioEventLoopInstrumentation());
    }
    ret.add(new PartitionInstrumentation());
    return ret;
  }
}
