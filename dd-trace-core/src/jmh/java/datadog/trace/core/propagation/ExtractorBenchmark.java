package datadog.trace.core.propagation;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 120, timeUnit = SECONDS)
@Measurement(iterations = 3, time = 120, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
@SuppressForbidden
public class ExtractorBenchmark {
  @Param({"datadog", "b3", "datadog,b3", "datadog:x-dth"})
  String extractPropagationStyles;

  List<Pair<String, String>> headers;
  HttpCodec.Extractor extractor;
  DDTraceId traceId;
  long spanId;

  @Setup(Level.Trial)
  public void setUp() {
    headers = new ArrayList<>();
    // The header strings will be fresh on every iteration, and we have a resetList
    // benchmark as a baseline to compare against
    headers.add(Pair.of(DatadogHttpCodec.TRACE_ID_KEY, "12345"));
    headers.add(Pair.of(DatadogHttpCodec.SPAN_ID_KEY, "23456"));
    headers.add(Pair.of(B3HttpCodec.TRACE_ID_KEY, "12345")); // HEX
    headers.add(Pair.of(B3HttpCodec.SPAN_ID_KEY, "23456")); // HEX
    headers.add(Pair.of("some-header-1", "ignored"));
    headers.add(Pair.of("some-header-2", "ignored"));
    headers.add(Pair.of("x-data-header-1", "ignored")); // starts like datadog headers
    headers.add(Pair.of("x-bware-header-1", "ignored")); // starts like b3 headers

    String[] propagationsAndFeatures = extractPropagationStyles.split(",");
    StringBuilder propagations = new StringBuilder();
    boolean addSeparator = false;
    for (String pAndA : propagationsAndFeatures) {
      if (addSeparator) {
        propagations.append(',');
      } else {
        addSeparator = true;
      }
      String[] propagationAndFeatures = pAndA.split(":");
      propagations.append(propagationAndFeatures[0]);
      for (int i = 1; i < propagationAndFeatures.length; i++) {
        String feature = propagationAndFeatures[i];
        switch (feature) {
          case "x-dth":
            headers.add(
                Pair.of(
                    DatadogHttpCodec.DATADOG_TAGS_KEY, "_dd.p.anytag=value,_dd.p.dm=934086a686-4"));
            break;
          default:
            System.out.println("Unknown benchmark feature " + feature + ". Will be ignored!");
        }
      }
    }

    System.setProperty("dd.propagation.style.extract", propagations.toString());
    DynamicConfig dynamicConfig =
        DynamicConfig.create()
            .setHeaderTags(Collections.emptyMap())
            .setBaggageMapping(Collections.emptyMap())
            .apply();
    extractor = HttpCodec.createExtractor(Config.get(), dynamicConfig::captureTraceConfig);

    if (extractPropagationStyles.startsWith("datadog")) {
      traceId = DDTraceId.from("12345");
      spanId = DDSpanId.from("23456");
    } else if (extractPropagationStyles.contains("b3")) {
      traceId = DDTraceId.fromHex("12345");
      spanId = DDSpanId.fromHex("23456");
    }
  }

  @Benchmark
  public void extractContext(Blackhole blackhole) {
    List<Pair<String, String>> list = resetList(headers);
    TagContext context = extractor.extract(list, LIST_VISITOR);
    ExtractedContext extractedContext = (ExtractedContext) context;
    blackhole.consume(context);
    blackhole.consume(list);
    assert extractedContext.getTraceId().equals(traceId);
    assert extractedContext.getSpanId() == spanId;
  }

  @Benchmark
  public void resetList(Blackhole blackhole) {
    List<Pair<String, String>> list = resetList(headers);
    blackhole.consume(list);
  }

  private static String dup(String input) {
    // Just calling new String(string) is not good enough since it will copy the hash code
    // as well which will most likely need to be computed on a new header straight off the
    // wire
    return new String(input.toLowerCase().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
  }

  private static List<Pair<String, String>> resetList(List<Pair<String, String>> list) {
    list.replaceAll(p -> Pair.of(dup(p.getLeft()), dup(p.getRight())));
    return list;
  }

  private static final AgentPropagation.ContextVisitor<List<Pair<String, String>>> LIST_VISITOR =
      new ListContextVisitor<>();

  private static final class ListContextVisitor<T extends List<Pair<String, String>>>
      implements AgentPropagation.ContextVisitor<T> {

    @Override
    public void forEachKey(T carrier, AgentPropagation.KeyClassifier classifier) {
      for (Pair<String, ?> entry : carrier) {
        if (null != entry.getRight()
            && !classifier.accept(entry.getLeft(), entry.getRight().toString())) {
          return;
        }
      }
    }
  }
}
