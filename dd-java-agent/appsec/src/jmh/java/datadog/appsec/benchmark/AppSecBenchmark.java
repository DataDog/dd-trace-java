package datadog.appsec.benchmark;

import static java.util.concurrent.TimeUnit.*;

import ch.qos.logback.classic.Logger;
import com.datadog.appsec.AppSecSystem;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.monitor.Monitoring;
import datadog.trace.api.gateway.*;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URI;
import java.net.URISyntaxException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okio.Timeout;
import org.openjdk.jmh.annotations.*;
import org.slf4j.LoggerFactory;

@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 2, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 3)
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
    SharedCommunicationObjects sharedCommunicationObjects = new SharedCommunicationObjects();
    sharedCommunicationObjects.monitoring = Monitoring.DISABLED;
    sharedCommunicationObjects.okHttpClient = new StubOkHttpClient();
    sharedCommunicationObjects.featuresDiscovery =
        new StubDDAgentFeaturesDiscovery(sharedCommunicationObjects.okHttpClient);

    AppSecSystem.start(gw, sharedCommunicationObjects);
    uri = new URIDefaultDataAdapter(new URI("http://localhost:8080/test"));
  }

  private void maliciousRequest() throws Exception {
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
  public void maliciousRequestDefault() throws Exception {
    maliciousRequest();
  }

  @Benchmark
  @Fork(jvmArgsAppend = "-DPOWERWAF_ENABLE_BYTE_BUFFERS=false")
  public void maliciousRequestNoByteBuffers() throws Exception {
    maliciousRequest();
  }

  private void normalRequest() {
    RequestContext context = gw.getCallback(Events.REQUEST_STARTED).get().getResult();
    gw.getCallback(Events.REQUEST_URI_RAW).apply(context, uri);
    gw.getCallback(Events.REQUEST_CLIENT_IP).apply(context, ip);
    gw.getCallback(Events.REQUEST_HEADER).accept(context, "User-Agent", "Mozilla/5.0");
    gw.getCallback(Events.REQUEST_HEADER_DONE).apply(context);
    gw.getCallback(Events.REQUEST_ENDED).apply(context);
  }

  @Benchmark
  public void normalRequestDefault() throws Exception {
    normalRequest();
  }

  @Benchmark
  @Fork(jvmArgsAppend = "-DPOWERWAF_ENABLE_BYTE_BUFFERS=false")
  public void normalRequestNoByteBuffers() throws Exception {
    normalRequest();
  }

  static class StubOkHttpClient extends OkHttpClient {
    @Override
    public Call newCall(final Request request) {
      final Response response =
          new Response.Builder()
              .request(request)
              .protocol(Protocol.HTTP_1_0)
              .code(200)
              .message("OK")
              .build();

      return new Call() {
        @Override
        public Request request() {
          return request;
        }

        @Override
        public Response execute() throws IOException {
          return response;
        }

        @Override
        public void enqueue(Callback responseCallback) {
          final Call thiz = this;
          new Thread(
                  () -> {
                    try {
                      responseCallback.onResponse(thiz, response);
                    } catch (IOException e) {
                      throw new UndeclaredThrowableException(e);
                    }
                  })
              .start();
        }

        @Override
        public void cancel() {}

        @Override
        public boolean isExecuted() {
          return true;
        }

        @Override
        public boolean isCanceled() {
          return false;
        }

        @Override
        public Timeout timeout() {
          return Timeout.NONE;
        }

        @Override
        public Call clone() {
          throw new UnsupportedOperationException();
        }
      };
    }
  }

  static class StubDDAgentFeaturesDiscovery extends DDAgentFeaturesDiscovery {
    public StubDDAgentFeaturesDiscovery(OkHttpClient client) {
      super(client, Monitoring.DISABLED, HttpUrl.get("http://localhost:8080/"), false, false);
    }

    @Override
    public void discover() {}

    @Override
    public boolean supportsMetrics() {
      return false;
    }

    @Override
    public String getMetricsEndpoint() {
      return null;
    }

    @Override
    public String getTraceEndpoint() {
      return "http://localhost:8080/";
    }

    @Override
    public String state() {
      return "";
    }

    @Override
    public boolean active() {
      return false;
    }
  }
}
