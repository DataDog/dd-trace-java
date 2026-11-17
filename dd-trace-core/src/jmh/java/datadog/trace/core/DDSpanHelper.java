package datadog.trace.core;

import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE;
import static datadog.trace.api.DDTags.RUNTIME_ID_TAG;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.propagation.PropagationTags;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface DDSpanHelper {
  static DDSpan create(
      @Nonnull String instrumentationName,
      final long timestampMicro,
      @Nonnull DDSpanContext context) {
    return DDSpan.create(instrumentationName, timestampMicro, context, null);
  }

  static void setAllTags(@Nonnull DDSpanContext context, Map<String, ?> map) {
    context.setAllTags(map);
  }

  /** A trace of one root span with a few tags, for benchmarks that serialize traces. */
  static List<DDSpan> singleRootTrace(@Nullable PropagationTags propagationTags) {
    Map<String, Object> tags = new HashMap<>();
    tags.put(RUNTIME_ID_TAG, "fdd790b3-4aeb-4517-9b84-cafcc0129c48");
    tags.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    tags.put("env", "prod");

    CoreTracer tracer =
        CoreTracer.builder()
            .strictTraceWrites(
                true) // Avoid any extra bookkeeping for traces since we write directly
            .build();
    DDTraceId traceId = DDTraceId.ONE;
    TraceCollector traceCollector = tracer.createTraceCollector(traceId);
    DDSpanContext rootContext =
        new DDSpanContext(
            traceId,
            2,
            DDSpanId.ZERO,
            null,
            "service",
            UTF8BytesString.create("operation"),
            UTF8BytesString.create("resource"),
            PrioritySampling.SAMPLER_KEEP,
            null,
            Collections.<String, String>emptyMap(),
            false,
            UTF8BytesString.create("type"),
            0,
            traceCollector,
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            propagationTags);
    setAllTags(rootContext, tags);
    DDSpan root = create("benchmark", System.currentTimeMillis() * 1000, rootContext);
    root.setResourceName(UTF8BytesString.create("benchmark"));
    List<DDSpan> trace = new ArrayList<>();
    trace.add(root);
    return trace;
  }
}
