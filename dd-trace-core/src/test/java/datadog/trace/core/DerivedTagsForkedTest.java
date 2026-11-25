package datadog.trace.core;

import static datadog.trace.api.KnownTags.COMPONENT;
import static datadog.trace.api.KnownTags.DB_INSTANCE;
import static datadog.trace.api.KnownTags.DD_INTEGRATION;
import static datadog.trace.api.KnownTags.DD_PEER_SERVICE_REMAPPED_FROM;
import static datadog.trace.api.KnownTags.DD_PEER_SERVICE_SOURCE;
import static datadog.trace.api.KnownTags.DD_SVC_SRC;
import static datadog.trace.api.KnownTags.HAZELCAST_INSTANCE;
import static datadog.trace.api.KnownTags.NET_PEER_NAME;
import static datadog.trace.api.KnownTags.PEER_HOSTNAME;
import static datadog.trace.api.KnownTags.PEER_SERVICE;
import static datadog.trace.api.KnownTags.RPC_SERVICE;
import static datadog.trace.api.KnownTags.SPAN_KIND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTagCodec;
import datadog.trace.api.KnownTags;
import datadog.trace.api.TagMap;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.naming.PeerServiceFormulas;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.tagprocessor.TagsPostProcessorFactory;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Derived tags on the dense store: the engine's rules, then a differential check that the base
 * prototype's formulas produce exactly what the post-processors they replace produce.
 */
@WithConfig(key = TracerConfig.TRACE_DENSE_TAGS_ENABLED, value = "true")
@WithConfig(key = TracerConfig.TRACE_PEER_SERVICE_DEFAULTS_ENABLED, value = "true")
@WithConfig(
    key = TracerConfig.TRACE_PEER_SERVICE_MAPPING,
    value = "orders-db:orders-renamed,explicit-svc:explicit-renamed")
@WithConfig(
    key = TracerConfig.TRACE_PEER_SERVICE_COMPONENT_OVERRIDES,
    value = "rmi-client:rmi-override")
@WithConfig(key = TraceInstrumentationConfig.TRACE_RESOURCE_RENAMING_ENABLED, value = "true")
@WithConfig(key = GeneralConfig.SERVICE_NAME, value = "checkout")
@WithConfig(key = GeneralConfig.VERSION, value = "1.2.3")
class DerivedTagsForkedTest extends DDCoreJavaSpecification {
  private static final long CUSTOM_A = KnownTags.DB_USER;
  private static final long CUSTOM_B = KnownTags.DB_OPERATION;
  private static final long OUTPUT = KnownTags.HTTP_ENDPOINT;

  // --- engine rules ---

  @Test
  void computesWhenReadAndOnlyOnce() {
    AtomicInteger calls = new AtomicInteger();
    SpanPrototype proto =
        SpanPrototype.builder()
            .initInstrumentationName("test")
            .initDerivedTag(
                OUTPUT,
                in -> {
                  calls.incrementAndGet();
                  return "endpoint:" + in.getString(CUSTOM_A);
                })
            .build();
    DDSpan span = (DDSpan) derivedTracer().buildSpan(proto, "op").start();
    span.setTag(CUSTOM_A, "/users");
    assertEquals(0, calls.get(), "nothing reads it yet");

    assertEquals("endpoint:/users", get(span, OUTPUT));
    assertEquals("endpoint:/users", get(span, OUTPUT));
    assertEquals(1, calls.get());
    span.finish();
  }

  @Test
  void explicitValueWinsAndFormulaNeverRuns() {
    AtomicInteger calls = new AtomicInteger();
    SpanPrototype proto =
        SpanPrototype.builder()
            .initInstrumentationName("test")
            .initDerivedTag(
                OUTPUT,
                in -> {
                  calls.incrementAndGet();
                  return "derived";
                })
            .build();
    DDSpan span = (DDSpan) derivedTracer().buildSpan(proto, "op").start();
    span.setTag(OUTPUT, "explicit");
    assertEquals("explicit", get(span, OUTPUT));
    assertEquals(0, calls.get());
    span.finish();
  }

  @Test
  void inputWriteInvalidatesUnrelatedWriteDoesNot() {
    AtomicInteger calls = new AtomicInteger();
    SpanPrototype proto =
        SpanPrototype.builder()
            .initInstrumentationName("test")
            .initDerivedTag(
                OUTPUT,
                in -> {
                  calls.incrementAndGet();
                  return in.isSet(CUSTOM_A) ? "route:" + in.getString(CUSTOM_A) : "none";
                })
            .build();
    DDSpan span = (DDSpan) derivedTracer().buildSpan(proto, "op").start();
    assertEquals("none", get(span, OUTPUT));

    span.setTag(KnownTags.HTTP_ROUTE, "unrelated"); // not read by the formula
    assertEquals("none", get(span, OUTPUT));
    assertEquals(1, calls.get());

    span.setTag(CUSTOM_A, "/users"); // read as absent before: still a dependency
    assertEquals("route:/users", get(span, OUTPUT));
    assertEquals(2, calls.get());
    span.finish();
  }

  @Test
  void removingAnExplicitValueBringsTheFormulaBack() {
    SpanPrototype proto =
        SpanPrototype.builder()
            .initInstrumentationName("test")
            .initDerivedTag(OUTPUT, in -> "derived")
            .build();
    DDSpan span = (DDSpan) derivedTracer().buildSpan(proto, "op").start();
    span.setTag(OUTPUT, "explicit");
    assertEquals("explicit", get(span, OUTPUT));
    span.spanContext().removeTag(OUTPUT);
    assertEquals("derived", get(span, OUTPUT));
    span.finish();
  }

  @Test
  void multipleOutputsAreAllOrNothing() {
    SpanPrototype proto =
        SpanPrototype.builder()
            .initInstrumentationName("test")
            .initDerivedTags(
                new long[] {CUSTOM_A, CUSTOM_B},
                (in, out) -> {
                  out.set(CUSTOM_A, "a");
                  out.set(CUSTOM_B, "b");
                })
            .build();
    DDSpan derived = (DDSpan) derivedTracer().buildSpan(proto, "op").start();
    assertEquals("b", get(derived, CUSTOM_B));
    assertEquals("a", get(derived, CUSTOM_A));
    derived.finish();

    DDSpan partlyExplicit = (DDSpan) derivedTracer().buildSpan(proto, "op").start();
    partlyExplicit.setTag(CUSTOM_A, "explicit");
    assertNull(get(partlyExplicit, CUSTOM_B), "one explicit target suppresses all");
    partlyExplicit.finish();

    DDSpan overwritten = (DDSpan) derivedTracer().buildSpan(proto, "op").start();
    assertEquals("b", get(overwritten, CUSTOM_B)); // runs the formula
    overwritten.setTag(CUSTOM_A, "explicit");
    assertEquals("explicit", get(overwritten, CUSTOM_A));
    assertNull(get(overwritten, CUSTOM_B), "its other output is undone");
    overwritten.finish();
  }

  @Test
  void cyclesLeaveTagsAbsent() {
    SpanPrototype proto =
        SpanPrototype.builder()
            .initInstrumentationName("test")
            .initDerivedTag(CUSTOM_A, in -> in.get(CUSTOM_B))
            .initDerivedTag(CUSTOM_B, in -> in.get(CUSTOM_A))
            .build();
    DDSpan span = (DDSpan) derivedTracer().buildSpan(proto, "op").start();
    assertNull(get(span, CUSTOM_A));
    assertNull(get(span, CUSTOM_B));
    span.finish();
  }

  @Test
  void undeclaredOutputIsRejected() {
    SpanPrototype proto =
        SpanPrototype.builder()
            .initInstrumentationName("test")
            .initDerivedTags(new long[] {CUSTOM_A}, (in, out) -> out.set(CUSTOM_B, "sneaky"))
            .build();
    DDSpan span = (DDSpan) derivedTracer().buildSpan(proto, "op").start();
    assertNull(get(span, CUSTOM_A));
    assertNull(get(span, CUSTOM_B));
    span.finish();
  }

  @Test
  void enumerationIncludesDerivedTags() {
    SpanPrototype proto =
        SpanPrototype.builder()
            .initInstrumentationName("test")
            .initDerivedTag(OUTPUT, in -> "derived")
            .build();
    DDSpan span = (DDSpan) derivedTracer().buildSpan(proto, "op").start();
    span.finish();
    TagMap tags = serializedTags(span);
    assertEquals("derived", tags.getString(KnownTagCodec.nameOf(OUTPUT)));
  }

  @Test
  void prototypeFormulasNeedDerivedTagsOn() {
    SpanPrototype proto =
        SpanPrototype.builder()
            .initInstrumentationName("test")
            .initDerivedTag(OUTPUT, in -> "derived")
            .build();
    DDSpan span = (DDSpan) tracer(false).buildSpan(proto, "op").start();
    assertNull(get(span, OUTPUT));
    span.finish();
  }

  @Test
  void peerServiceComesFromTheFirstPrecursorAndRecordsItsSource() {
    DDSpan span = (DDSpan) derivedTracer().buildSpan("test", "op").start();
    span.setTag(SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    span.setTag(PEER_HOSTNAME, "db-host");
    span.setTag(DB_INSTANCE, "orders");
    assertEquals("orders", get(span, PEER_SERVICE));
    assertEquals(Tags.DB_INSTANCE, get(span, DD_PEER_SERVICE_SOURCE));
    assertTrue(span.spanContext().isDerivedTag(PEER_SERVICE));
    span.finish();
  }

  // --- deferred tags ---

  @Test
  void deferredTagIsExtractedWhenReadAndOnlyOnce() {
    AtomicInteger calls = new AtomicInteger();
    DDSpan span = (DDSpan) derivedTracer().buildSpan("test", "op").start();
    span.spanContext()
        .setDeferredTag(
            KnownTags.ERROR_STACK,
            "source",
            s -> {
              calls.incrementAndGet();
              return "extracted:" + s;
            });
    span.toString(); // diagnostics must not extract
    assertEquals(0, calls.get());
    assertEquals("extracted:source", get(span, KnownTags.ERROR_STACK));
    assertEquals("extracted:source", get(span, KnownTags.ERROR_STACK));
    assertEquals(1, calls.get());
    span.finish();
  }

  @Test
  void deferredTagIsExtractedBySerialization() {
    AtomicInteger calls = new AtomicInteger();
    DDSpan span = (DDSpan) derivedTracer().buildSpan("test", "op").start();
    span.spanContext()
        .setDeferredTag(
            KnownTags.ERROR_STACK,
            "source",
            s -> {
              calls.incrementAndGet();
              return "extracted";
            });
    assertEquals(0, calls.get(), "nothing on the application thread reads it");
    // ListWriter does the serializer's tag processing inside write(), so finishing extracts here.
    span.finish();
    assertEquals(1, calls.get());
    assertEquals(
        "extracted", serializedTags(span).getString(KnownTagCodec.nameOf(KnownTags.ERROR_STACK)));
    assertEquals(1, calls.get(), "extracted once, then stored");
  }

  @Test
  void explicitWriteOrRemovalReplacesAPendingValue() {
    AtomicInteger calls = new AtomicInteger();
    DDSpan written = (DDSpan) derivedTracer().buildSpan("test", "op").start();
    written
        .spanContext()
        .setDeferredTag(KnownTags.ERROR_STACK, "source", s -> calls.incrementAndGet());
    written.setTag(KnownTags.ERROR_STACK, "explicit");
    assertEquals("explicit", get(written, KnownTags.ERROR_STACK));
    written.finish();

    DDSpan removed = (DDSpan) derivedTracer().buildSpan("test", "op").start();
    removed
        .spanContext()
        .setDeferredTag(KnownTags.ERROR_STACK, "source", s -> calls.incrementAndGet());
    removed.spanContext().removeTag(KnownTags.ERROR_STACK);
    assertNull(get(removed, KnownTags.ERROR_STACK));
    removed.finish();
    assertEquals(0, calls.get());
  }

  @Test
  void failingExtractorLeavesTheTagAbsent() {
    DDSpan span = (DDSpan) derivedTracer().buildSpan("test", "op").start();
    span.spanContext()
        .setDeferredTag(
            KnownTags.ERROR_STACK,
            "source",
            s -> {
              throw new IllegalStateException("boom");
            });
    span.finish();
    assertNull(serializedTags(span).getString(KnownTagCodec.nameOf(KnownTags.ERROR_STACK)));
  }

  @Test
  void deferredErrorStackMatchesTheEagerOne() {
    Throwable error = new IllegalStateException("db down", new RuntimeException("cause"));
    String expected = errorTags(tracer(false), error);
    String actual = errorTags(derivedTracer(), error);
    assertTrue(expected.contains("IllegalStateException"), expected);
    assertEquals(expected, actual);
  }

  private String errorTags(CoreTracer tracer, Throwable error) {
    DDSpan span = (DDSpan) tracer.buildSpan("test", "op").start();
    span.addThrowable(error);
    span.finish();
    TagMap tags = serializedTags(span);
    return tags.getString(DDTags.ERROR_TYPE)
        + "|"
        + tags.getString(DDTags.ERROR_MSG)
        + "|"
        + tags.getString(KnownTagCodec.nameOf(KnownTags.ERROR_STACK))
        + "|"
        + span.isError();
  }

  // --- differential check: formulas vs the post-processors they replace ---

  private static final long[] PRECURSORS = {
    InstrumentationTagIds.KAFKA_BOOTSTRAP_SERVERS,
    HAZELCAST_INSTANCE,
    InstrumentationTagIds.COUCHBASE_SEED_NODES,
    NET_PEER_NAME,
    InstrumentationTagIds.CASSANDRA_CONTACT_POINTS,
    RPC_SERVICE,
    DB_INSTANCE,
    PEER_HOSTNAME
  };

  private static final String[] COMPONENTS = {
    "java-kafka",
    "hazelcast-sdk",
    "couchbase-client",
    "java-cassandra",
    "grpc-client",
    "rmi-client",
    "java-aws-sdk",
    "java-jdbc",
    null
  };

  private static final String[] KINDS = {
    Tags.SPAN_KIND_CLIENT, Tags.SPAN_KIND_PRODUCER, Tags.SPAN_KIND_SERVER
  };

  private static final long[] COMPARED = {
    PEER_SERVICE, DD_PEER_SERVICE_SOURCE, DD_PEER_SERVICE_REMAPPED_FROM, DD_INTEGRATION, DD_SVC_SRC
  };

  @Test
  void formulasMatchThePostProcessors() {
    CoreTracer legacy = tracer(false);
    CoreTracer derived = tracer(true);
    List<String> mismatches = new ArrayList<>();
    int cases = 0;
    derivedByPath[0] = derivedByPath[1] = 0;
    for (String component : COMPONENTS) {
      for (String kind : KINDS) {
        for (int present = 0; present < (1 << PRECURSORS.length); ++present) {
          for (int explicit = 0; explicit < 2; ++explicit) {
            for (int viaPrototype = 0; viaPrototype < 2; ++viaPrototype) {
              Case c = new Case(component, kind, present, explicit == 1, viaPrototype == 1);
              String expected = c.run(legacy);
              String actual = c.run(derived);
              cases++;
              if (!expected.equals(actual)) {
                mismatches.add(
                    c + "\n  post-processors: " + expected + "\n  formulas:        " + actual);
              }
            }
          }
        }
      }
    }
    assertTrue(derivedByPath[0] > 0, "formulas produced peer.service on plain spans");
    assertTrue(derivedByPath[1] > 0, "formulas produced peer.service on prototype spans");
    assertTrue(
        mismatches.isEmpty(),
        mismatches.size() + " of " + cases + " cases differ; first:\n" + first(mismatches, 5));
  }

  private static String first(List<String> lines, int n) {
    return String.join("\n", lines.subList(0, Math.min(n, lines.size())));
  }

  /** How many cases had a formula produce peer.service: plain spans, then prototype spans. */
  private final int[] derivedByPath = new int[2];

  private final class Case {
    final String component;
    final String kind;
    final int present;
    final boolean explicitPeerService;
    final boolean viaPrototype;

    Case(String component, String kind, int present, boolean explicit, boolean viaPrototype) {
      this.component = component;
      this.kind = kind;
      this.present = present;
      this.explicitPeerService = explicit;
      this.viaPrototype = viaPrototype;
    }

    /** Builds the span on {@code tracer}, finishes it, and renders the compared tags. */
    String run(CoreTracer tracer) {
      DDSpan span;
      if (viaPrototype) {
        // What ClientDecorator builds: kind and component up front, plus its own formula.
        SpanPrototype.Builder proto =
            SpanPrototype.builder().initInstrumentationName("test").initKind(kind);
        if (component != null) {
          proto.initComponentAndIntegration(component);
          proto.initDerivedTags(
              PeerServiceFormulas.TARGETS,
              SpanNaming.instance()
                  .namingSchema()
                  .peerService()
                  .formula(component, Config.get().getPeerServiceMapping()));
        }
        span = (DDSpan) tracer.buildSpan(proto.build(), "op").start();
      } else {
        span = (DDSpan) tracer.buildSpan("test", "op").start();
        span.setTag(SPAN_KIND, kind);
        if (component != null) {
          span.setTag(COMPONENT, component);
        }
      }
      for (int i = 0; i < PRECURSORS.length; ++i) {
        if ((present & (1 << i)) != 0) {
          // orders-db is mapped; the others aren't
          span.setTag(PRECURSORS[i], i == 6 ? "orders-db" : "value-" + i);
        }
      }
      if (explicitPeerService) {
        span.setTag(PEER_SERVICE, "explicit-svc");
      }
      span.finish();
      TagMap tags = serializedTags(span);
      if (span.spanContext().isDerivedTag(PEER_SERVICE)) {
        derivedByPath[viaPrototype ? 1 : 0]++;
      }
      StringBuilder out = new StringBuilder();
      for (long id : COMPARED) {
        out.append(KnownTagCodec.nameOf(id))
            .append('=')
            .append(tags.getString(KnownTagCodec.nameOf(id)))
            .append(' ');
      }
      return out.toString();
    }

    @Override
    public String toString() {
      return "component="
          + component
          + " kind="
          + kind
          + " precursors="
          + Integer.toBinaryString(present)
          + " explicit="
          + explicitPeerService
          + " prototype="
          + viaPrototype;
    }
  }

  // --- differential check: internal tags vs InternalTagsAdder ---

  @Test
  void internalTagFormulasMatchTheProcessor() {
    TagsPostProcessorFactory.withAddInternalTags(true);
    try {
      CoreTracer legacy = tracer(false);
      CoreTracer derived = tracer(true);
      String ddService = Config.get().getServiceName();
      for (String service : new String[] {ddService, ddService.toUpperCase(), "orders-db", null}) {
        for (String explicitVersion : new String[] {null, "9.9"}) {
          String expected = internalTagsOf(legacy, service, explicitVersion);
          String actual = internalTagsOf(derived, service, explicitVersion);
          assertEquals(expected, actual, "service=" + service + " version=" + explicitVersion);
        }
      }
    } finally {
      TagsPostProcessorFactory.withAddInternalTags(false);
    }
  }

  private String internalTagsOf(CoreTracer tracer, String service, String explicitVersion) {
    DDSpan span = (DDSpan) tracer.buildSpan("test", "op").start();
    if (service != null) {
      span.setServiceName(service);
    }
    if (explicitVersion != null) {
      span.setTag(KnownTags.VERSION, explicitVersion);
    }
    span.finish();
    TagMap tags = serializedTags(span);
    return "base="
        + tags.getString(KnownTagCodec.nameOf(KnownTags.DD_BASE_SERVICE))
        + " version="
        + tags.getString(KnownTagCodec.nameOf(KnownTags.VERSION));
  }

  // --- differential check: http.endpoint vs HttpEndpointPostProcessor ---

  private static final String[] METHODS = {null, "GET"};
  private static final String[] ROUTES = {null, "", "*", "*/*", "/owners/{ownerId}"};
  private static final int URL_SOURCES = 5; // none, url, lazy url, path, url and path

  @Test
  void endpointFormulaMatchesThePostProcessor() {
    CoreTracer legacy = tracer(false);
    CoreTracer derived = tracer(true);
    List<String> mismatches = new ArrayList<>();
    int cases = 0;
    int derivedCases = 0;
    for (String method : METHODS) {
      for (String route : ROUTES) {
        for (int urls = 0; urls < URL_SOURCES; ++urls) {
          String expected = endpointOf(legacy, method, route, urls, null);
          DDSpan[] span = {null};
          String actual = endpointOf(derived, method, route, urls, span);
          if (span[0].spanContext().isDerivedTag(KnownTags.HTTP_ENDPOINT) || actual != null) {
            derivedCases++;
          }
          cases++;
          if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            mismatches.add(
                "method="
                    + method
                    + " route="
                    + route
                    + " urls="
                    + urls
                    + ": post-processor="
                    + expected
                    + " formula="
                    + actual);
          }
        }
      }
    }
    assertTrue(derivedCases > 0, "the formula produced http.endpoint");
    assertTrue(
        mismatches.isEmpty(),
        mismatches.size() + " of " + cases + " cases differ:\n" + first(mismatches, 5));
  }

  @Test
  void explicitEndpointNowWins() {
    // A known shift: HttpEndpointPostProcessor overwrites an explicit http.endpoint; the formula,
    // like every formula, leaves an explicit value alone.
    DDSpan span = (DDSpan) derivedTracer().buildSpan("test", "op").start();
    span.setTag(KnownTags.HTTP_METHOD, "GET");
    span.setTag(KnownTags.HTTP_URL, "http://localhost:8080/owners/42");
    span.setTag(KnownTags.HTTP_ENDPOINT, "explicit");
    span.finish();
    assertEquals(
        "explicit", serializedTags(span).getString(KnownTagCodec.nameOf(KnownTags.HTTP_ENDPOINT)));
  }

  private String endpointOf(
      CoreTracer tracer, String method, String route, int urls, DDSpan[] spanOut) {
    DDSpan span = (DDSpan) tracer.buildSpan("test", "servlet.request").start();
    span.setTag(SPAN_KIND, Tags.SPAN_KIND_SERVER);
    if (method != null) {
      span.setTag(KnownTags.HTTP_METHOD, method);
    }
    if (route != null) {
      span.setTag(KnownTags.HTTP_ROUTE, route);
    }
    switch (urls) {
      case 1:
        span.setTag(KnownTags.HTTP_URL, "http://localhost:8080/owners/42?visit=7");
        break;
      case 2:
        span.setTag(
            KnownTags.HTTP_URL, URIUtils.lazyValidURL("http", "localhost", 8080, "/owners/42"));
        break;
      case 3:
        span.setTag(KnownTags.URL_PATH, "/owners/42/pets/9");
        break;
      case 4:
        span.setTag(KnownTags.HTTP_URL, "http://localhost:8080/users/0d8f3c2a");
        span.setTag(KnownTags.URL_PATH, "/owners/42");
        break;
      default:
        break;
    }
    span.finish();
    if (spanOut != null) {
      spanOut[0] = span;
    }
    return serializedTags(span).getString(KnownTagCodec.nameOf(KnownTags.HTTP_ENDPOINT));
  }

  // --- helpers ---

  private CoreTracer derivedTracer() {
    return tracer(true);
  }

  private CoreTracer tracer(boolean derivedTags) {
    return tracerBuilder().writer(new ListWriter()).derivedTags(derivedTags).build();
  }

  /** The span's tags as the serializer sees them, after both post-processor chains. */
  private static TagMap serializedTags(DDSpan span) {
    TagMap[] captured = {null};
    span.processTagsAndBaggage(metadata -> captured[0] = metadata.getTags().copy(), 0);
    return captured[0];
  }

  /** Ids of the precursors {@link InstrumentationTags} names only as strings. */
  private static final class InstrumentationTagIds {
    static final long KAFKA_BOOTSTRAP_SERVERS =
        KnownTagCodec.keyOf(InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS);
    static final long COUCHBASE_SEED_NODES =
        KnownTagCodec.keyOf(InstrumentationTags.COUCHBASE_SEED_NODES);
    static final long CASSANDRA_CONTACT_POINTS =
        KnownTagCodec.keyOf(InstrumentationTags.CASSANDRA_CONTACT_POINTS);
  }

  /** A known tag's value, read by name through the span's public path. */
  private static Object get(DDSpan span, long tagId) {
    return span.getTag(KnownTagCodec.nameOf(tagId));
  }
}
