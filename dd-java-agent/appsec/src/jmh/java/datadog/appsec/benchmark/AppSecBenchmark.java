package datadog.appsec.benchmark;

import static java.util.concurrent.TimeUnit.*;

import ch.qos.logback.classic.Logger;
import com.datadog.appsec.AppSecSystem;
import datadog.trace.api.gateway.*;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import java.net.URI;
import java.net.URISyntaxException;
import org.openjdk.jmh.annotations.*;
import org.slf4j.LoggerFactory;

@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MILLISECONDS)
public class AppSecBenchmark {

  // To exclude log messages printing influence onto the benchmark
  // we need suppress all log messages
  static {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    root.setLevel(ch.qos.logback.classic.Level.OFF);
  }

  private InstrumentationGateway gw;
  private URIDataAdapter uri;
  private String ip = "0.0.0.0";

  @Setup(Level.Trial)
  public void setUp() throws URISyntaxException {
    gw = new InstrumentationGateway();
    AppSecSystem.start(gw);
    uri = new URIDefaultDataAdapter(new URI("http://localhost:8080/test"));
  }

  @Benchmark
  public void maliciousRequest() throws Exception {
    RequestContext context = gw.getCallback(Events.REQUEST_STARTED).get().getResult();
    gw.getCallback(Events.REQUEST_URI_RAW).apply(context, uri);
    gw.getCallback(Events.REQUEST_CLIENT_IP).apply(context, ip);
    gw.getCallback(Events.REQUEST_HEADER).accept(context, "User-Agent", "Arachni/v1");
    Flow<?> flow = gw.getCallback(Events.REQUEST_HEADER_DONE).apply(context);
    if (!flow.getAction().isBlocking()) {
      throw new Exception("Request should be blocked");
    }
    gw.getCallback(Events.REQUEST_ENDED).apply(context);
  }

  @Benchmark
  public void normalRequest() {
    RequestContext context = gw.getCallback(Events.REQUEST_STARTED).get().getResult();
    gw.getCallback(Events.REQUEST_URI_RAW).apply(context, uri);
    gw.getCallback(Events.REQUEST_CLIENT_IP).apply(context, ip);
    gw.getCallback(Events.REQUEST_HEADER).accept(context, "User-Agent", "Mozilla/5.0");
    gw.getCallback(Events.REQUEST_HEADER_DONE).apply(context);
    gw.getCallback(Events.REQUEST_ENDED).apply(context);
  }
}
