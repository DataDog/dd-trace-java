package datadog.trace.core.propagation;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.Config;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.Pair;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/** Extracts B3 headers whose span id is valid or malformed in a common way. */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 5, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Threads(4)
@Fork(value = 2, jvmArgsAppend = "-DTEST_LOG_LEVEL=info")
@SuppressForbidden
public class B3SpanIdExtractBenchmark {
  @Param({"b3multi", "b3single"})
  String style;

  /** valid: 16 hex digits; oversized: a 128-bit id sent as the span id; nonHex: garbage. */
  @Param({"valid", "oversized", "nonHex"})
  String spanId;

  List<Pair<String, String>> headers;
  HttpCodec.Extractor extractor;

  @Setup(Level.Trial)
  public void setUp() {
    String traceIdHex = "463ac35c9f6413ad";
    String spanIdHex;
    switch (spanId) {
      case "valid":
        spanIdHex = "a2fb4a1d1a96d312";
        break;
      case "oversized":
        spanIdHex = "463ac35c9f6413ad48485a3953bb6124";
        break;
      default:
        spanIdHex = "zzzzzzzzzzzzzzzz";
    }
    headers = new ArrayList<>();
    if (style.equals("b3multi")) {
      headers.add(Pair.of(B3HttpCodec.TRACE_ID_KEY, traceIdHex));
      headers.add(Pair.of(B3HttpCodec.SPAN_ID_KEY, spanIdHex));
      headers.add(Pair.of(B3HttpCodec.SAMPLING_PRIORITY_KEY, "1"));
    } else {
      headers.add(Pair.of(B3HttpCodec.B3_KEY, traceIdHex + "-" + spanIdHex + "-1"));
    }
    headers.add(Pair.of("user-agent", "benchmark"));
    headers.add(Pair.of("accept", "*/*"));

    System.setProperty("dd.trace.propagation.style.extract", style);
    DynamicConfig dynamicConfig =
        DynamicConfig.create()
            .setHeaderTags(Collections.emptyMap())
            .setBaggageMapping(Collections.emptyMap())
            .apply();
    extractor = HttpCodec.createExtractor(Config.get(), dynamicConfig::captureTraceConfig);

    boolean extracted = extract() instanceof ExtractedContext;
    if (extracted != spanId.equals("valid")) {
      throw new IllegalStateException(style + "/" + spanId + " extracted=" + extracted);
    }
  }

  @Benchmark
  public TagContext extract() {
    return extractor.extract(freshCopy(headers), LIST_VISITOR);
  }

  /** Header strings arrive fresh off the wire, without a cached hash code. */
  private static List<Pair<String, String>> freshCopy(List<Pair<String, String>> list) {
    List<Pair<String, String>> copy = new ArrayList<>(list.size());
    for (Pair<String, String> p : list) {
      copy.add(Pair.of(dup(p.getLeft()), dup(p.getRight())));
    }
    return copy;
  }

  private static String dup(String input) {
    return new String(input.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
  }

  private static final AgentPropagation.ContextVisitor<List<Pair<String, String>>> LIST_VISITOR =
      (carrier, classifier) -> {
        for (Pair<String, String> entry : carrier) {
          if (!classifier.accept(entry.getLeft(), entry.getRight())) {
            return;
          }
        }
      };
}
