package datadog.telemetry;

import static datadog.trace.api.config.GeneralConfig.ENV;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.GeneralConfig.VERSION;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.monitor.Monitoring;
import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.Properties;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

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

  private static class NoopTelemetryClient extends TelemetryClient {

    public NoopTelemetryClient(
        OkHttpClient okHttpClient,
        HttpRetryPolicy.Factory httpRetryPolicy,
        HttpUrl url,
        String apiKey) {
      super(okHttpClient, httpRetryPolicy, url, apiKey);
    }

    @Override
    public Result sendHttpRequest(Request.Builder httpRequestBuilder) {
      return Result.SUCCESS;
    }
  }

  private Properties props;
  TelemetryService service;

  @Setup(Level.Iteration)
  public void setUp() {
    props = new Properties();
    props.setProperty(SERVICE_NAME, "benchmark-service");
    props.setProperty(ENV, "benchmark-env");
    props.setProperty(VERSION, "1");
    DDAgentFeaturesDiscovery featuresDiscovery = new MockFeaturesDiscovery(false);
    HttpUrl url =
        new HttpUrl.Builder()
            .scheme("https")
            .host("example.com")
            .addPathSegment("path")
            .addQueryParameter("key", "value")
            .build();
    NoopTelemetryClient telemetryClient =
        new NoopTelemetryClient(null, HttpRetryPolicy.Factory.NEVER_RETRY, url, "");
    service = TelemetryService.build(featuresDiscovery, telemetryClient, null, false, false);
  }

  @Benchmark
  public void appStartedEventBenchmark() {
    Config c = Config.get(props);
    TelemetryRunnable telemetryRunnable = new TelemetryRunnable(service, new ArrayList<>());
    telemetryRunnable.collectConfigChanges();
    service.sendAppStartedEvent();
  }
}
