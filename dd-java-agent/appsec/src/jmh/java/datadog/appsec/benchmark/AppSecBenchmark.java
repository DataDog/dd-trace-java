package datadog.appsec.benchmark;

import static datadog.trace.api.gateway.Events.EVENTS;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.appsec.AppSecSystem;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.http.client.HttpClient;
import datadog.http.client.HttpRequest;
import datadog.http.client.HttpResponse;
import datadog.http.client.HttpUrl;
import datadog.metrics.api.Monitoring;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.InstrumentationGateway;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
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
  private CallbackProvider cbp;
  private SubscriptionService ss;
  private String method = "GET";
  private URIDataAdapter uri;
  private String ip = "0.0.0.0";
  private int port = 5555;

  @Setup(Level.Trial)
  public void setUp() throws URISyntaxException {
    gw = new InstrumentationGateway();
    cbp = gw.getCallbackProvider(RequestContextSlot.APPSEC);
    ss = gw.getSubscriptionService(RequestContextSlot.APPSEC);
    SharedCommunicationObjects sharedCommunicationObjects = new SharedCommunicationObjects();
    sharedCommunicationObjects.monitoring = Monitoring.DISABLED;
    sharedCommunicationObjects.agentHttpClient = new StubHttpClient();
    sharedCommunicationObjects.setFeaturesDiscovery(
        new StubDDAgentFeaturesDiscovery(sharedCommunicationObjects.agentHttpClient));

    AppSecSystem.start(ss, sharedCommunicationObjects);
    uri = new URIDefaultDataAdapter(new URI("http://localhost:8080/test"));
  }

  private void maliciousRequest() throws Exception {
    RequestContext context =
        new Context(cbp.getCallback(EVENTS.requestStarted()).get().getResult());
    cbp.getCallback(EVENTS.requestMethodUriRaw()).apply(context, method, uri);
    cbp.getCallback(EVENTS.requestClientSocketAddress()).apply(context, ip, port);
    cbp.getCallback(EVENTS.requestHeader()).accept(context, "User-Agent", "Arachni/v1");
    Flow<?> flow = cbp.getCallback(EVENTS.requestHeaderDone()).apply(context);
    if (!flow.getAction().isBlocking()) {
      throw new Exception("Request should be blocked");
    }
    cbp.getCallback(EVENTS.requestEnded()).apply(context, null);
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
    RequestContext context =
        new Context(cbp.getCallback(EVENTS.requestStarted()).get().getResult());
    cbp.getCallback(EVENTS.requestMethodUriRaw()).apply(context, method, uri);
    cbp.getCallback(EVENTS.requestClientSocketAddress()).apply(context, ip, port);
    cbp.getCallback(EVENTS.requestHeader()).accept(context, "User-Agent", "Mozilla/5.0");
    Flow<?> flow = cbp.getCallback(EVENTS.requestHeaderDone()).apply(context);
    cbp.getCallback(EVENTS.requestEnded()).apply(context, null);
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

  static class StubHttpClient implements HttpClient {
    private static final HttpResponse STUB_RESPONSE = new StubHttpResponse();

    @Override
    public HttpResponse execute(HttpRequest request) {
      return STUB_RESPONSE;
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request) {
      return CompletableFuture.completedFuture(STUB_RESPONSE);
    }
  }

  static class StubHttpResponse implements HttpResponse {
    @Override
    public int code() {
      return 200;
    }

    @Override
    public boolean isSuccessful() {
      return true;
    }

    @Override
    public String header(String name) {
      return null;
    }

    @Override
    public List<String> headers(String name) {
      return Collections.emptyList();
    }

    @Override
    public Set<String> headerNames() {
      return Collections.emptySet();
    }

    @Override
    public InputStream body() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public String bodyAsString() {
      return "";
    }

    @Override
    public void close() {}
  }

  static class StubDDAgentFeaturesDiscovery extends DDAgentFeaturesDiscovery {
    public StubDDAgentFeaturesDiscovery(HttpClient client) {
      super(client, Monitoring.DISABLED, HttpUrl.parse("http://localhost:8080/"), false, false);
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

  static class Context implements RequestContext {
    private final Object data;

    public Context(Object data) {
      this.data = data;
    }

    @Override
    public Object getData(RequestContextSlot slot) {
      if (slot == RequestContextSlot.APPSEC) {
        return data;
      } else {
        return null;
      }
    }

    @Override
    public TraceSegment getTraceSegment() {
      return TraceSegment.NoOp.INSTANCE;
    }

    @Override
    public void setBlockResponseFunction(BlockResponseFunction blockResponseFunction) {}

    @Override
    public BlockResponseFunction getBlockResponseFunction() {
      return null;
    }

    @Override
    public <T> T getOrCreateMetaStructTop(String key, Function<String, T> defaultValue) {
      return null;
    }

    @Override
    public void close() throws IOException {}
  }
}
