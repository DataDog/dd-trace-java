package datadog.trace.core.taginterceptor;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTagCodec;
import datadog.trace.api.KnownTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures the tag-routing SCREEN — the {@code needsIntercept} check that runs on every {@code
 * setTag}, whether or not the tag is routed — against the name-keyed string switch it replaced.
 *
 * <p><b>Both implementations live here, as arms.</b> The old switch is deleted from production, so
 * there is no flag to turn on and off in one binary, and a master-vs-branch two-jar comparison
 * would conflate this change with everything else that moved. So {@link #screenByName} runs a
 * verbatim copy of the switch as it stood before this change, and {@link #screenById} runs the
 * shipped path. One binary, no drift, and the baseline arm is frozen the moment it is written.
 *
 * <p><b>Read throughput here, not allocation.</b> This is one of the rare CPU-not-allocation
 * levers: {@code gc.alloc.rate.norm} should come out flat across every arm, and if it does not,
 * something is wrong — that is the check, not the result. Which makes the signal the fragile one:
 * run {@code -f3} at minimum (per-fork inlining bimodality is the failure mode for a method this
 * small) and treat the numbers as directional. The acceptance number is macro; see the PetClinic
 * harness.
 *
 * <p><b>What this deliberately over-states.</b> It isolates the screen from the span work around
 * it. In a real span the screen is a small slice of create/tag/finish, so a percentage here is not
 * a percentage there — {@code SpanCreationBenchmark} is where the in-situ effect shows up.
 *
 * <p>The arms are chosen around what the change actually does:
 *
 * <ul>
 *   <li><b>miss</b> — a known tag that is not routed ({@code http.route}). The common case, and the
 *       one that decides the whole thing: the old path fell through the whole {@code lookupswitch}
 *       to a set lookup; the new one is a {@code keyOf} probe plus a mask test.
 *   <li><b>hit</b> — a routed tag ({@code resource.name}). The old path found a case label early.
 *   <li><b>custom</b> — a tag with no registry id at all. The one case that plausibly got
 *       <i>worse</i>: {@code keyOf} probes and misses, and the set lookup still runs.
 *   <li><b>bundle</b> — the per-bundle screen over a 7-tag web-shaped map with nothing routed in
 *       it, so the scan runs to completion. This is the O(n) form, and the baseline for the
 *       running-OR idea (one mask for a whole map) if that lands later.
 * </ul>
 *
 * <p>{@code splitServiceTags} is a {@link Param} because it is the one input the id cannot answer —
 * user configuration naming any tag at all — and because the new path skips the set lookup outright
 * when it is unset, which is only visible on the miss arms.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 3, jvmArgsAppend = "-DTEST_LOG_LEVEL=warn")
public class TagInterceptorScreenBenchmark {

  /** A known tag the interceptor does not route. */
  private static final String MISS_TAG = Tags.HTTP_ROUTE;

  /** A routed tag; the old switch found it as a case label. */
  private static final String HIT_TAG = DDTags.RESOURCE_NAME;

  /** No registry id at all, so only a name lookup can recognise it. */
  private static final String CUSTOM_TAG = "app.checkout.step";

  /**
   * Off: {@code splitServiceTags} empty, the shipped configuration. On: populated, which forces the
   * set lookup back onto the miss path in both arms.
   */
  @Param({"off", "on"})
  String splitByTags;

  TagInterceptor interceptor;
  Set<String> splitServiceTags;
  TagMap webBundle;

  @Setup
  public void setup() {
    this.splitServiceTags =
        "on".equals(splitByTags)
            ? new HashSet<>(Arrays.asList("sn.tenant", "sn.region"))
            : Collections.<String>emptySet();
    this.interceptor =
        new TagInterceptor(
            false, "inferred-service", this.splitServiceTags, new RuleFlags(), false);

    // Web-server-shaped bundle with nothing routed in it, so the screen scans every entry -- the
    // shape the trace-level bundles in CoreTracer actually have.
    this.webBundle = TagMap.create(7);
    this.webBundle.set(Tags.COMPONENT, "tomcat-server");
    this.webBundle.set(Tags.HTTP_ROUTE, "/owners/{ownerId}");
    this.webBundle.set(Tags.HTTP_HOSTNAME, "localhost");
    this.webBundle.set(Tags.HTTP_USER_AGENT, "curl/8.4.0");
    this.webBundle.set(Tags.PEER_PORT, 80);
    this.webBundle.set(InstrumentationTags.SERVLET_PATH, "/owners/42");
    this.webBundle.set("app.build", "2026.09.1");
  }

  // ---- shipped path: keyOf + mask ----

  @Benchmark
  public boolean screenById_miss() {
    return interceptor.needsIntercept(MISS_TAG);
  }

  @Benchmark
  public boolean screenById_hit() {
    return interceptor.needsIntercept(HIT_TAG);
  }

  @Benchmark
  public boolean screenById_custom() {
    return interceptor.needsIntercept(CUSTOM_TAG);
  }

  @Benchmark
  public boolean bundleScreenById() {
    return interceptor.needsIntercept(webBundle);
  }

  // ---- baseline: the name switch as it stood before this change ----

  @Benchmark
  public boolean screenByName_miss() {
    return needsInterceptByName(MISS_TAG);
  }

  @Benchmark
  public boolean screenByName_hit() {
    return needsInterceptByName(HIT_TAG);
  }

  @Benchmark
  public boolean screenByName_custom() {
    return needsInterceptByName(CUSTOM_TAG);
  }

  @Benchmark
  public boolean bundleScreenByName() {
    for (TagMap.EntryReader entry : webBundle) {
      if (needsInterceptByName(entry.tag())) return true;
    }
    return false;
  }

  // ---- dispatch MECHANISM, handler bodies excluded ----

  /**
   * The dispatch half, isolated to its lookup. Both arms return a distinct int per tag instead of
   * running a handler, so what is being compared is the {@code lookupswitch}-on-string-hashes shape
   * against the {@code tableswitch}-on-dense-serials shape and nothing else.
   *
   * <p>Copying the real 22-case dispatch in here to serve as a baseline would reintroduce exactly
   * the duplication this change removes, and it would go stale silently. So this measures the
   * mechanism honestly and claims nothing about the handlers, which are unchanged.
   */
  @Benchmark
  public int dispatchLookupById() {
    return lookupBySerial(KnownTagCodec.keyOf(HIT_TAG));
  }

  @Benchmark
  public int dispatchLookupByName() {
    return lookupByName(HIT_TAG);
  }

  private static int lookupBySerial(long tagId) {
    switch (KnownTagCodec.serialNum(tagId)) {
      case KnownTags.RESOURCE_NAME_SERIAL_NUM:
        return 1;
      case KnownTags.DB_STATEMENT_SERIAL_NUM:
        return 2;
      case KnownTags.SERVICE_SERIAL_NUM:
        return 3;
      case KnownTags.PEER_SERVICE_SERIAL_NUM:
        return 4;
      case KnownTags.MANUAL_KEEP_SERIAL_NUM:
        return 5;
      case KnownTags.MANUAL_DROP_SERIAL_NUM:
        return 6;
      case KnownTags.ASM_KEEP_SERIAL_NUM:
        return 7;
      case KnownTags.AI_GUARD_KEEP_SERIAL_NUM:
        return 8;
      case KnownTags.SAMPLING_PRIORITY_SERIAL_NUM:
        return 9;
      case KnownTags.DD_P_TS_SERIAL_NUM:
        return 10;
      case KnownTags.DD_P_DEBUG_SERIAL_NUM:
        return 11;
      case KnownTags.SERVLET_CONTEXT_SERIAL_NUM:
        return 12;
      case KnownTags.SPAN_TYPE_SERIAL_NUM:
        return 13;
      case KnownTags.DD1_SR_EAUSR_SERIAL_NUM:
        return 14;
      case KnownTags.ERROR_SERIAL_NUM:
        return 15;
      case KnownTags.HTTP_STATUS_CODE_SERIAL_NUM:
        return 16;
      case KnownTags.HTTP_METHOD_SERIAL_NUM:
        return 17;
      case KnownTags.HTTP_URL_SERIAL_NUM:
        return 18;
      case KnownTags.DD_ORIGIN_SERIAL_NUM:
        return 19;
      case KnownTags.DD_MEASURED_SERIAL_NUM:
        return 20;
      case KnownTags.SPAN_KIND_SERIAL_NUM:
        return 21;
      default:
        return 0;
    }
  }

  private static int lookupByName(String tag) {
    switch (tag) {
      case DDTags.RESOURCE_NAME:
        return 1;
      case Tags.DB_STATEMENT:
        return 2;
      case DDTags.SERVICE_NAME:
      case "service":
        return 3;
      case Tags.PEER_SERVICE:
        return 4;
      case DDTags.MANUAL_KEEP:
        return 5;
      case DDTags.MANUAL_DROP:
        return 6;
      case Tags.ASM_KEEP:
        return 7;
      case Tags.AI_GUARD_KEEP:
        return 8;
      case Tags.SAMPLING_PRIORITY:
        return 9;
      case Tags.PROPAGATED_TRACE_SOURCE:
        return 10;
      case Tags.PROPAGATED_DEBUG:
        return 11;
      case InstrumentationTags.SERVLET_CONTEXT:
        return 12;
      case DDTags.SPAN_TYPE:
        return 13;
      case DDTags.ANALYTICS_SAMPLE_RATE:
        return 14;
      case Tags.ERROR:
        return 15;
      case Tags.HTTP_STATUS:
        return 16;
      case Tags.HTTP_METHOD:
        return 17;
      case Tags.HTTP_URL:
        return 18;
      case DDTags.ORIGIN_KEY:
        return 19;
      case DDTags.MEASURED:
        return 20;
      case Tags.SPAN_KIND:
        return 21;
      default:
        return 0;
    }
  }

  /**
   * The screen as it stood before this change: one {@code lookupswitch} over 22 case labels, then a
   * set lookup for anything that falls through. Verbatim apart from reading the benchmark's own
   * {@code splitServiceTags} field.
   */
  private boolean needsInterceptByName(String tag) {
    switch (tag) {
      case DDTags.RESOURCE_NAME:
      case Tags.DB_STATEMENT:
      case DDTags.SERVICE_NAME:
      case "service":
      case Tags.PEER_SERVICE:
      case DDTags.MANUAL_KEEP:
      case DDTags.MANUAL_DROP:
      case Tags.ASM_KEEP:
      case Tags.AI_GUARD_KEEP:
      case Tags.SAMPLING_PRIORITY:
      case Tags.PROPAGATED_TRACE_SOURCE:
      case Tags.PROPAGATED_DEBUG:
      case InstrumentationTags.SERVLET_CONTEXT:
      case DDTags.SPAN_TYPE:
      case DDTags.ANALYTICS_SAMPLE_RATE:
      case Tags.ERROR:
      case Tags.HTTP_STATUS:
      case Tags.HTTP_METHOD:
      case Tags.HTTP_URL:
      case DDTags.ORIGIN_KEY:
      case DDTags.MEASURED:
      case Tags.SPAN_KIND:
        return true;

      default:
        return splitServiceTags.contains(tag);
    }
  }
}
