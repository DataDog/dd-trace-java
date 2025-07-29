package datadog.telemetry.configsources;

import static datadog.trace.api.config.GeneralConfig.ENV;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.GeneralConfig.VERSION;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.monitor.Monitoring;
import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.trace.api.Config;
import datadog.trace.core.baggage.BaggagePropagator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@OutputTimeUnit(MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
public class ConfigSourcesBenchmark {
  
  // Simple mock for DDAgentFeaturesDiscovery for benchmarking purposes
  private static class MockFeaturesDiscovery extends DDAgentFeaturesDiscovery {
    private final boolean supportsDataStreams;

    public MockFeaturesDiscovery(boolean supportsDataStreams) {
      super(null, Monitoring.DISABLED, null, true, true);
      this.supportsDataStreams = supportsDataStreams;
    }

    @Override
    public void discover() {}

    @Override
    public void discoverIfOutdated() {}

    @Override
    public boolean supportsDataStreams() {
      return supportsDataStreams;
    }
  }
  
  private Properties props;
  TelemetryRunnable telemetryRunnable;
  @Setup(Level.Trial)
  public void setUp() {
    props = new Properties();
    props.setProperty(SERVICE_NAME, "benchmark-service");
    props.setProperty(ENV, "benchmark-env");
    props.setProperty(VERSION, "1");
    DDAgentFeaturesDiscovery featuresDiscovery = new MockFeaturesDiscovery(false);
    TelemetryService service = TelemetryService.build(featuresDiscovery, null, null, false, false);
    telemetryRunnable = new TelemetryRunnable(service, new ArrayList<>());
  }
  @Benchmark
  public void runTelemetry() {
    Config c = Config.get(props);
    telemetryRunnable.run();
  }
}
