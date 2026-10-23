package datadog.trace.core.propagation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import datadog.context.Context;
import datadog.context.propagation.Propagators;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Pins down what default compound extraction (Datadog, then W3C trace context) produces for a
 * corpus of header combinations: the extracted context, and what a local root span built on it
 * injects downstream.
 *
 * <p>The expected outputs in {@code compound-extraction.golden} were captured before extraction was
 * restructured, so any change in behavior shows up as a mismatch. To regenerate, or to dump the
 * full cartesian corpus ({@code -Dcompound.corpus=full}) for a diff between two revisions, run with
 * {@code -Dcompound.golden.dump=<file>} (or the {@code COMPOUND_CORPUS} / {@code
 * COMPOUND_GOLDEN_DUMP} environment variables, which reach a Gradle test JVM).
 */
class CompoundExtractionGoldenTest extends DDCoreJavaSpecification {

  private static final String GOLDEN = "compound-extraction.golden";

  private static final String TID_HI = "640cfd8d00000000";
  private static final String TID_LO = "00000000075bcd15";
  private static final String DD_TRACE_ID = Long.toString(Long.parseLong(TID_LO, 16));
  private static final String PARENT = "00f067aa0ba902b7";
  private static final String DD_SAME_PARENT = Long.toString(Long.parseLong(PARENT, 16));
  private static final String RV = "c8b1e2d3f4a596";

  private static final String[] DD_PRESENCE = {"none", "match", "matchOtherParent", "mismatch"};

  private static final String[] DD_PRIORITY_FULL = {null, "0", "1", "2"};
  private static final String[] DD_PRIORITY = {"1", "0"};

  private static final String[] DD_TAGS_FULL = {
    null,
    "_dd.p.dm=-1",
    "_dd.p.dm=-4,_dd.p.usr=123",
    "_dd.p.tid=" + TID_HI + ",_dd.p.dm=-1",
    "_dd.p.dm=-1,_dd.p.tid=zzz",
    "_dd.p.dm=-1,_dd.p.big=" + repeat('x', 600)
  };
  private static final String[] DD_TAGS = {
    null, "_dd.p.dm=-1", "_dd.p.tid=" + TID_HI + ",_dd.p.dm=-1", "_dd.p.dm=-1,_dd.p.tid=zzz"
  };

  private static final String[] TRACEPARENT = {
    null,
    "00-" + TID_HI + TID_LO + "-" + PARENT + "-01",
    "00-" + TID_HI + TID_LO + "-" + PARENT + "-00",
    "00-xyz",
    "00-0000000000000000" + TID_LO + "-" + PARENT + "-01"
  };

  private static final String[] TRACESTATE = {
    null,
    "dd=s:1;p:" + PARENT + ";t.dm:-1",
    "dd=s:2;o:synthetics;p:" + PARENT + ";t.usr:123",
    "dd=s:-1;p:" + PARENT,
    "dd=s:1;p:" + PARENT + ";t.dm:-1,ot=rv:" + RV + ";th:0",
    "dd=s:0;p:" + PARENT + ",ot=th:8;rv:" + RV,
    "ot=th:0;rv:" + RV + ",vendor=x",
    "foo=bar,dd=p:" + PARENT + ";t.tid:" + TID_HI,
    "dd=s:1;;;p:",
    ""
  };

  @Test
  void compoundExtractionMatchesGolden() throws IOException {
    boolean full = "full".equals(setting("compound.corpus", "COMPOUND_CORPUS"));
    Map<String, String> actual = run(full);

    String dump = setting("compound.golden.dump", "COMPOUND_GOLDEN_DUMP");
    if (dump != null) {
      StringBuilder out = new StringBuilder();
      actual.forEach((id, result) -> out.append(id).append('\t').append(result).append('\n'));
      Files.write(Paths.get(dump), out.toString().getBytes(UTF_8));
      return;
    }
    if (full) {
      return;
    }

    Map<String, String> expected = readGolden();
    assertEquals(expected.keySet(), actual.keySet(), "corpus changed; regenerate the golden");
    List<String> mismatches = new ArrayList<>();
    for (Map.Entry<String, String> entry : expected.entrySet()) {
      String got = actual.get(entry.getKey());
      if (!entry.getValue().equals(got)) {
        mismatches.add(
            entry.getKey() + "\n  expected: " + entry.getValue() + "\n  actual:   " + got);
      }
    }
    if (!mismatches.isEmpty()) {
      fail(
          mismatches.size()
              + " of "
              + expected.size()
              + " cases differ, first:\n"
              + String.join("\n", mismatches.subList(0, Math.min(10, mismatches.size()))));
    }
  }

  private Map<String, String> run(boolean full) {
    Map<String, String> results = new LinkedHashMap<>();
    CoreTracer tracer =
        tracerBuilder()
            .writer(new ListWriter())
            .idGenerationStrategy(IdGenerationStrategy.fromName("SEQUENTIAL"))
            .build();
    try {
      for (Map<String, String> headers : corpus(full)) {
        String previous = results.put(id(headers), describe(tracer, headers));
        assertNull(previous, "duplicate case id " + id(headers));
      }
    } finally {
      tracer.close();
    }
    return results;
  }

  private static List<Map<String, String>> corpus(boolean full) {
    List<Map<String, String>> corpus = new ArrayList<>();
    String[] priorities = full ? DD_PRIORITY_FULL : DD_PRIORITY;
    String[] tags = full ? DD_TAGS_FULL : DD_TAGS;
    for (String presence : DD_PRESENCE) {
      String[] presencePriorities = "none".equals(presence) ? new String[] {null} : priorities;
      String[] presenceTags = "none".equals(presence) ? new String[] {null} : tags;
      for (String priority : presencePriorities) {
        for (String ddTags : presenceTags) {
          for (int tp = 0; tp < TRACEPARENT.length; ++tp) {
            for (int ts = 0; ts < TRACESTATE.length; ++ts) {
              // the curated corpus varies traceparent only against one tracestate
              if (!full && tp != 1 && ts != 1) {
                continue;
              }
              Map<String, String> headers = new LinkedHashMap<>();
              if (!"none".equals(presence)) {
                headers.put("x-datadog-trace-id", "mismatch".equals(presence) ? "42" : DD_TRACE_ID);
                headers.put(
                    "x-datadog-parent-id",
                    "matchOtherParent".equals(presence) ? "7" : DD_SAME_PARENT);
                putIfNotNull(headers, "x-datadog-sampling-priority", priority);
                putIfNotNull(headers, "x-datadog-tags", ddTags);
              }
              putIfNotNull(headers, "traceparent", TRACEPARENT[tp]);
              putIfNotNull(headers, "tracestate", TRACESTATE[ts]);
              corpus.add(headers);
            }
          }
        }
      }
    }
    return corpus;
  }

  private static String describe(CoreTracer tracer, Map<String, String> headers) {
    Context extracted =
        Propagators.defaultPropagator()
            .extract(Context.root(), headers, (carrier, visitor) -> carrier.forEach(visitor));
    AgentSpan extractedSpan = AgentSpan.fromContext(extracted);
    AgentSpanContext parent = extractedSpan == null ? null : extractedSpan.spanContext();

    StringBuilder sb = new StringBuilder();
    describeContext(sb, parent);

    AgentSpan root =
        parent == null
            ? tracer.buildSpan("golden", "root").start()
            : tracer.buildSpan("golden", "root").asChildOf(parent).start();
    Map<String, String> injected = new TreeMap<>();
    Propagators.defaultPropagator().inject(root, injected, Map::put);
    root.finish();

    String injectedText = injected.toString();
    if (!(parent instanceof ExtractedContext)) {
      // a new trace's 128-bit id embeds a timestamp
      injectedText = maskNewTraceId(injectedText, root.getTraceId());
    }
    sb.append(" | injected=").append(injectedText);
    return sb.toString();
  }

  private static void describeContext(StringBuilder sb, AgentSpanContext context) {
    if (!(context instanceof TagContext)) {
      sb.append(context == null ? "null" : context.getClass().getSimpleName());
      return;
    }
    TagContext tc = (TagContext) context;
    sb.append(tc.getClass().getSimpleName())
        .append(" trace=")
        .append(tc.getTraceId().toHexString())
        .append(" span=")
        .append(DDSpanId.toHexString(tc.getSpanId()))
        .append(" priority=")
        .append(tc.getSamplingPriority())
        .append(" origin=")
        .append(tc.getOrigin())
        .append(" tags=")
        .append(tc.getTags() == null ? null : new TreeMap<>(tc.getTags()));
    if (tc instanceof ExtractedContext) {
      PropagationTags ptags = ((ExtractedContext) tc).getPropagationTags();
      sb.append(" ptags[priority=")
          .append(ptags.getSamplingPriority())
          .append(" origin=")
          .append(ptags.getOrigin())
          .append(" lastParent=")
          .append(ptags.getLastParentId())
          .append(" tracestate=")
          .append(ptags.getW3CTracestate())
          .append(" dd=")
          .append(ptags.headerValue(PropagationTags.HeaderType.DATADOG))
          .append(" w3c=")
          .append(ptags.headerValue(PropagationTags.HeaderType.W3C))
          .append(" map=")
          .append(new TreeMap<>(ptags.createTagMap()))
          .append(']');
    }
    List<AgentSpanLink> links = tc.getTerminatedSpanLinks();
    if (links != null && !links.isEmpty()) {
      sb.append(" links=[");
      for (AgentSpanLink link : links) {
        sb.append('{')
            .append(link.traceId().toHexString())
            .append(' ')
            .append(DDSpanId.toHexString(link.spanId()))
            .append(" flags=")
            .append(link.traceFlags())
            .append(" state=")
            .append(link.traceState())
            .append(" attrs=")
            .append(new TreeMap<>(link.attributes().asMap()))
            .append('}');
      }
      sb.append(']');
    }
  }

  private static String maskNewTraceId(String text, DDTraceId traceId) {
    String hex = traceId.toHexString();
    String hi = hex.length() > 16 ? hex.substring(0, hex.length() - 16) : null;
    String lo = hex.substring(Math.max(0, hex.length() - 16));
    String result = text;
    if (hi != null && !hi.matches("0+")) {
      result = result.replace(hi, "<new-hi>");
    }
    result = result.replace(lo, "<new-lo>");
    return result.replace(Long.toUnsignedString(traceId.toLong()), "<new-lo-dec>");
  }

  private static String id(Map<String, String> headers) {
    StringBuilder sb = new StringBuilder();
    headers.forEach(
        (key, value) -> {
          if (sb.length() > 0) {
            sb.append(' ');
          }
          sb.append(key)
              .append('=')
              .append(value.length() > 200 ? value.length() + "chars" : value);
        });
    return sb.length() == 0 ? "<no headers>" : sb.toString();
  }

  private static Map<String, String> readGolden() throws IOException {
    Map<String, String> golden = new LinkedHashMap<>();
    InputStream in = CompoundExtractionGoldenTest.class.getResourceAsStream(GOLDEN);
    assertNotNull(in, "missing " + GOLDEN);
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        int tab = line.indexOf('\t');
        golden.put(line.substring(0, tab), line.substring(tab + 1));
      }
    }
    return golden;
  }

  private static String setting(String property, String env) {
    String value = System.getProperty(property);
    return value != null ? value : System.getenv(env);
  }

  private static void putIfNotNull(Map<String, String> headers, String key, String value) {
    if (value != null) {
      headers.put(key, value);
    }
  }

  private static String repeat(char c, int count) {
    StringBuilder sb = new StringBuilder(count);
    for (int i = 0; i < count; ++i) {
      sb.append(c);
    }
    return sb.toString();
  }
}
