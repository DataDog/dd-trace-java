package datadog.trace.core;

import static datadog.trace.api.gateway.Events.EVENTS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.Config;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.lambda.LambdaAppSecHandler;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** Lambda request parsing and context creation, with lightweight callbacks and no WAF or I/O. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Threads(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 2,
    jvmArgsAppend = {
      "-Dlogback.configurationFile=lambda-benchmark-logback.xml",
      "-Ddd.trace.startup.logs=false",
      "-Ddd.trace.health.metrics.enabled=false"
    })
public class LambdaRequestStartBenchmark {
  @Param({"true", "false"})
  public boolean resolverEnabled;

  @Param({"source", "forwarded", "ipv6", "malformed", "longChain"})
  public String payload;

  private CoreTracer tracer;
  private AgentTracer.TracerAPI originalTracer;
  private ByteArrayInputStream event;

  @Setup
  public void setup(Blackhole blackhole) {
    // Each parameter combination runs in its own JVM. Set flags before Config is initialized.
    System.setProperty("dd.trace.client-ip.resolver.enabled", String.valueOf(resolverEnabled));
    System.setProperty("dd.trace.client-ip.enabled", "false");
    System.setProperty("dd.appsec.enabled", "true");
    System.setProperty("dd.remote_config.enabled", "false");
    System.setProperty("dd.telemetry.enabled", "false");
    if (Config.get().isTraceClientIpResolverEnabled() != resolverEnabled) {
      throw new IllegalStateException(
          "Resolver configuration was initialized before benchmark setup");
    }
    originalTracer = AgentTracer.get();
    tracer = CoreTracer.builder().writer(new DropWriter(blackhole)).build();
    AgentTracer.forceRegister(tracer);
    ActiveSubsystems.APPSEC_ACTIVE = true;
    SubscriptionService callbacks = tracer.getSubscriptionService(RequestContextSlot.APPSEC);
    callbacks.registerCallback(EVENTS.requestStarted(), () -> new Flow.ResultFlow<>(new Object()));
    callbacks.registerCallback(
        EVENTS.requestMethodUriRaw(),
        (ctx, method, uri) -> {
          blackhole.consume(uri);
          return Flow.ResultFlow.empty();
        });
    callbacks.registerCallback(
        EVENTS.requestHeader(), (ctx, name, value) -> blackhole.consume(value));
    callbacks.registerCallback(
        EVENTS.requestClientSocketAddress(),
        (ctx, ip, port) -> {
          blackhole.consume(ip);
          return Flow.ResultFlow.empty();
        });
    callbacks.registerCallback(
        EVENTS.requestInferredClientAddress(),
        (ctx, ip) -> {
          blackhole.consume(ip);
          return Flow.ResultFlow.empty();
        });
    callbacks.registerCallback(EVENTS.requestHeaderDone(), ctx -> Flow.ResultFlow.empty());
    String header;
    switch (payload) {
      case "source":
        header = "";
        break;
      case "forwarded":
        header = "1.2.3.4, 10.0.0.2";
        break;
      case "ipv6":
        header = "2001:4860:4860::8888";
        break;
      case "malformed":
        header = "invalid, unknown, [broken";
        break;
      case "longChain":
        StringBuilder chain = new StringBuilder();
        for (int i = 0; i < 32; i++) {
          chain.append("10.0.0.2, ");
        }
        header = chain.append("1.2.3.4").toString();
        break;
      default:
        throw new IllegalArgumentException(payload);
    }
    String json =
        "{\"httpMethod\":\"POST\",\"path\":\"/login\",\"headers\":{\"host\":\"api.example.com\""
            + (header.isEmpty() ? "" : ",\"x-forwarded-for\":\"" + header + "\"")
            + "},\"requestContext\":{\"httpMethod\":\"POST\",\"identity\":{\"sourceIp\":\"10.0.0.5\"}}}";
    event = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    if (LambdaAppSecHandler.processRequestStart(event) == null) {
      throw new IllegalStateException("Benchmark payload did not create an AppSec context");
    }
    LambdaAppSecHandler.processRequestEnd(null);
  }

  @Benchmark
  public AgentSpanContext requestStart() {
    AgentSpanContext context = LambdaAppSecHandler.processRequestStart(event);
    // Clears per-invocation state without adding span creation or response processing costs.
    LambdaAppSecHandler.processRequestEnd(null);
    return context;
  }

  @TearDown
  public void tearDown() {
    ActiveSubsystems.APPSEC_ACTIVE = false;
    AgentTracer.forceRegister(originalTracer);
    tracer.close();
  }
}
