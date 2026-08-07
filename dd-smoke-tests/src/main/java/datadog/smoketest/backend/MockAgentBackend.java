package datadog.smoketest.backend;

import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import datadog.trace.agent.test.server.http.JavaTestHttpServer.HandlerApi;
import datadog.trace.test.agent.decoder.DecodedMessage;
import datadog.trace.test.agent.decoder.DecodedTrace;
import datadog.trace.test.agent.decoder.Decoder;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process mock-agent {@link AgentBackend} wrapping the testing {@link JavaTestHttpServer}. It
 * answers the tracer's {@code /info} probe, decodes the trace payloads the tracer submits (v0.4 /
 * v0.5 / v1.0 msgpack) via the shared {@link Decoder}, serves Remote Configuration (see {@link
 * #remoteConfig()}), and 200s everything else so the app's other agent calls don't error out.
 *
 * <p>Remaining backend-specific capture surfaces (EVP-proxy / DSM) would hang off this concrete
 * type rather than the common {@link AgentBackend} facade.
 */
public final class MockAgentBackend extends AgentBackend {
  /**
   * Agent-info payload advertising the endpoints and capabilities the tracer negotiates from, //
   * mirroring the real agent (and the Groovy smoke mock)
   */
  private static final String INFO_BODY =
      "{\"version\":\"7.77.0\","
          + "\"endpoints\":[\"/v0.4/traces\",\"/v0.5/traces\",\"/v1.0/traces\",\"/telemetry/proxy/\"],"
          + "\"client_drop_p0s\":true,"
          + "\"span_meta_structs\":true,"
          + "\"long_running_spans\":true}";

  /** JSON mime type. */
  private static final String JSON = "application/json";

  /** The "no configs" remote-config response, as served until a test pushes one. */
  private static final String NO_REMOTE_CONFIG = "{}";

  private final List<DecodedTrace> traces = new CopyOnWriteArrayList<>();
  private final List<Map<String, Object>> telemetry = new CopyOnWriteArrayList<>();
  private final List<Map<String, Object>> remoteConfigPolls = new CopyOnWriteArrayList<>();
  private volatile String remoteConfigResponse = NO_REMOTE_CONFIG;
  private volatile JavaTestHttpServer server;

  MockAgentBackend() {}

  @Override
  public void start() {
    if (this.server != null) {
      return;
    }
    this.server = JavaTestHttpServer.httpServer(this::serverSpecs);
  }

  private void serverSpecs(JavaTestHttpServer server) {
    server.handlers(
        h -> {
          // Trace endpoints are method-agnostic prefix handlers: the tracer submits
          // traces with PUT (DDAgentApi), not POST.
          h.prefix("/info", this::sendInfo);
          h.prefix("/v1.0/traces", api -> collect(api, TraceFormat.V1));
          h.prefix("/v0.5/traces", api -> collect(api, TraceFormat.V05));
          h.prefix("/v0.4/traces", api -> collect(api, TraceFormat.V04));
          h.prefix("/v0.7/config", this::serveRemoteConfig);
          h.prefix("/telemetry/proxy", this::collectTelemetry);
          // Everything else just succeeds.
          h.all(api -> api.getResponse().status(200).send());
        });
  }

  private void sendInfo(HandlerApi api) {
    api.getResponse().status(200).sendWithType(JSON, INFO_BODY);
  }

  private void collect(HandlerApi api, TraceFormat format) {
    DecodedMessage message = format.decode(api.getRequest().getBody());
    this.traces.addAll(message.getTraces());
    api.getResponse().status(200).sendWithType(JSON, "{}");
  }

  private void serveRemoteConfig(HandlerApi api) {
    byte[] body = api.getRequest().getBody();
    if (body != null && body.length > 0) {
      this.remoteConfigPolls.add(AgentBackendMessages.decodeMessage(body));
    }
    api.getResponse().status(200).sendWithType(JSON, this.remoteConfigResponse);
  }

  private void pushRemoteConfig(String path, String config) {
    this.remoteConfigResponse = AgentBackendMessages.remoteConfigResponse(path, config);
  }

  private void collectTelemetry(HandlerApi api) {
    byte[] body = api.getRequest().getBody();
    if (body != null && body.length > 0) {
      this.telemetry.add(AgentBackendMessages.decodeMessage(body));
    }
    api.getResponse().status(202).send();
  }

  @Override
  public int port() {
    return url().getPort();
  }

  @Override
  public URI url() {
    JavaTestHttpServer running = this.server;
    if (running == null) {
      throw new IllegalStateException("MockAgentBackend not started — call start() first");
    }
    return running.getAddress();
  }

  @Override
  public Traces traces() {
    return new Traces(() -> new ArrayList<>(this.traces));
  }

  @Override
  public Telemetry telemetry() {
    return new Telemetry(() -> new ArrayList<>(this.telemetry));
  }

  @Override
  public RemoteConfig remoteConfig() {
    return new RemoteConfig(this::pushRemoteConfig, () -> new ArrayList<>(this.remoteConfigPolls));
  }

  @Override
  public void clear() {
    this.traces.clear();
    this.telemetry.clear();
    this.remoteConfigPolls.clear();
    this.remoteConfigResponse = NO_REMOTE_CONFIG;
  }

  @Override
  public void close() {
    JavaTestHttpServer running = this.server;
    if (running != null) {
      this.server = null;
      running.close();
    }
  }

  /** The msgpack trace-payload formats the mock agent accepts, each decoded by {@link Decoder}. */
  private enum TraceFormat {
    V04 {
      @Override
      DecodedMessage decode(byte[] body) {
        return Decoder.decodeV04(body);
      }
    },
    V05 {
      @Override
      DecodedMessage decode(byte[] body) {
        return Decoder.decodeV05(body);
      }
    },
    V1 {
      @Override
      DecodedMessage decode(byte[] body) {
        return Decoder.decodeV1(body);
      }
    };

    abstract DecodedMessage decode(byte[] body);
  }
}
