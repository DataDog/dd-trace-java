package datadog.appsec.benchmark;

import static datadog.trace.api.gateway.Events.EVENTS;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.appsec.AppSecSystem;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.monitor.Monitoring;
import datadog.trace.api.TraceSegment;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.InstrumentationGateway;
import datadog.trace.api.gateway.RequestContext;
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
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 2, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 3)
public class AppSecBenchmark {

  static {
    BenchmarkUtil.disableLogging();
  }

  private InstrumentationGateway gw;
  private String method = "GET";
  private URIDataAdapter uri;
  private String ip = "0.0.0.0";
  private int port = 5555;

  @Setup(Level.Trial)
  public void setUp() throws URISyntaxException {
    gw = new InstrumentationGateway();
    SharedCommunicationObjects sharedCommunicationObjects = new SharedCommunicationObjects();
    sharedCommunicationObjects.monitoring = Monitoring.DISABLED;
    sharedCommunicationObjects.okHttpClient = new StubOkHttpClient();
    sharedCommunicationObjects.featuresDiscovery =
        new StubDDAgentFeaturesDiscovery(sharedCommunicationObjects.okHttpClient);

    AppSecSystem.start(null, gw, sharedCommunicationObjects);
    uri = new URIDefaultDataAdapter(new URI("http://localhost:8080/test"));
  }

  private void maliciousRequest() throws Exception {
    RequestContext<Object> context =
        new Context(gw.getCallback(EVENTS.requestStarted()).get().getResult());
    gw.getCallback(EVENTS.requestMethodUriRaw()).apply(context, method, uri);
    gw.getCallback(EVENTS.requestClientSocketAddress()).apply(context, ip, port);
    gw.getCallback(EVENTS.requestHeader()).accept(context, "User-Agent", "Arachni/v1");
    Flow<?> flow = gw.getCallback(EVENTS.requestHeaderDone()).apply(context);
    if (!flow.getAction().isBlocking()) {
      throw new Exception("Request should be blocked");
    }
    gw.getCallback(EVENTS.requestEnded()).apply(context, null);
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
    RequestContext<Object> context =
        new Context(gw.getCallback(EVENTS.requestStarted()).get().getResult());
    gw.getCallback(EVENTS.requestMethodUriRaw()).apply(context, method, uri);
    gw.getCallback(EVENTS.requestClientSocketAddress()).apply(context, ip, port);
    gw.getCallback(EVENTS.requestHeader()).accept(context, "User-Agent", "Mozilla/5.0");
    Flow<?> flow = gw.getCallback(EVENTS.requestHeaderDone()).apply(context);
    gw.getCallback(EVENTS.requestEnded()).apply(context, null);
  }

  @Benchmark
  public void normalRequestDefault() throws Exception {
    normalRequest();
  }

  @Fork(jvmArgsAppend = "-Ddd.appsec.waf.metrics=false")
  @Benchmark
  public void normalRequestNoWafMetrics() {
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

  static class Context implements RequestContext<Object> {
    private final Object data;

    public Context(Object data) {
      this.data = data;
    }

    @Override
    public Object getData() {
      return data;
    }

    @Override
    public TraceSegment getTraceSegment() {
      return TraceSegment.NoOp.INSTANCE;
    }
  }
}
