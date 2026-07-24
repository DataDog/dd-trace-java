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
 * In-process mock-agent {@link TraceBackend} wrapping the testing {@link JavaTestHttpServer}. It
 * answers the tracer's {@code /info} probe, decodes the trace payloads the tracer submits (v0.4 /
 * v0.5 / v1.0 msgpack) via the shared {@link Decoder}, and 200s everything else so the app's other
 * agent calls (telemetry, remote-config, ...) don't error out.
 *
 * <p>Backend-specific capture surfaces (remote-config / EVP-proxy / DSM — Q10/S10) will hang off
 * this concrete type rather than the common {@link TraceBackend} facade.
 */
public final class MockAgentBackend implements TraceBackend {
  // Minimal agent-info payload advertising the trace endpoints, mirroring the real agent's /info
  // (see SimpleAgentMock). The tracer negotiates its trace encoding from this list.
  private static final String INFO_BODY =
      "{\"version\":\"7.77.0\",\"endpoints\":[\"/v0.4/traces\",\"/v0.5/traces\",\"/v1.0/traces\"]}";
  private static final String JSON = "application/json";

  private final List<DecodedTrace> traces = new CopyOnWriteArrayList<>();
  private final List<Map<String, Object>> telemetry = new CopyOnWriteArrayList<>();
  private volatile JavaTestHttpServer server;
  private boolean shared;

  MockAgentBackend() {}

  /**
   * Marks this backend as shared across multiple apps — declare it as its own
   * {@code @RegisterExtension} field so it is started/reset/closed once rather than by each app
   * (S6/Q8).
   */
  public MockAgentBackend shared() {
    this.shared = true;
    return this;
  }

  @Override
  public boolean isShared() {
    return this.shared;
  }

  @Override
  public void start() {
    if (this.server != null) {
      return;
    }
    this.server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h -> {
                      // Trace endpoints are method-agnostic prefix handlers: the tracer submits
                      // traces with PUT (DDAgentApi), not POST.
                      h.prefix("/info", this::sendInfo);
                      h.prefix("/v1.0/traces", api -> collect(api, TraceFormat.V1));
                      h.prefix("/v0.5/traces", api -> collect(api, TraceFormat.V05));
                      h.prefix("/v0.4/traces", api -> collect(api, TraceFormat.V04));
                      h.prefix("/telemetry/proxy", this::collectTelemetry);
                      // Everything else (remote-config, ...) just succeeds.
                      h.all(api -> api.getResponse().status(200).send());
                    }));
  }

  private void sendInfo(HandlerApi api) {
    api.getResponse().status(200).sendWithType(JSON, INFO_BODY);
  }

  private void collect(HandlerApi api, TraceFormat format) {
    DecodedMessage message = format.decode(api.getRequest().getBody());
    this.traces.addAll(message.getTraces());
    api.getResponse().status(200).sendWithType(JSON, "{}");
  }

  private void collectTelemetry(HandlerApi api) {
    byte[] body = api.getRequest().getBody();
    if (body != null && body.length > 0) {
      this.telemetry.add(TelemetryDecoder.decodeMessage(body));
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
  public void clear() {
    this.traces.clear();
    this.telemetry.clear();
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
