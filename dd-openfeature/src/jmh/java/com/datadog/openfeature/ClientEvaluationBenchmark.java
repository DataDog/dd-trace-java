package com.datadog.openfeature;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.bridge.v1.ConfigurationSource;
import com.datadog.openfeature.internal.bridge.v1.EventTransport;
import com.datadog.openfeature.internal.bridge.v1.HealthMetrics;
import com.datadog.openfeature.internal.bridge.v1.ShutdownSource;
import com.datadog.openfeature.internal.bridge.v1.SpanEnricher;
import com.datadog.openfeature.internal.config.TestSettings;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Evaluation cost through the OpenFeature client, as an application pays it: the client, the
 * provider's hooks and a started runtime, with evaluation metrics, evaluation counts and exposure
 * logging. {@link ScaleSuiteBenchmark} calls the provider directly and measures none of these.
 *
 * <p>The runtime receives the suite's configuration from a fake Remote Configuration source and
 * posts its events to a transport that drops them, so its background threads aggregate and
 * serialize as in production, without network I/O. The OpenTelemetry API is the no-op one.
 *
 * <p>{@code subjects=repeated} evaluates each case with its own targeting key, so the exposure of a
 * case is sent once and then deduplicated. {@code subjects=unique} evaluates each case for another
 * subject every time, like a service whose requests come from many users: every exposure is new,
 * and evaluation counts open a bucket per subject up to the per-flag cap. The subject keys are
 * built in the setup, so the benchmark itself allocates nothing per operation.
 *
 * <p>Only {@code scale/variations} and some {@code scale/splits} cases log exposures: in the other
 * case files, every allocation has {@code doLog=false}.
 *
 * <p>Above a few hundred thousand evaluations per second, the flag evaluation queue overflows and
 * evaluations skip their event, so the measured cost leaves part of it out, the more so the faster
 * the code. The teardown prints how many events were dropped. {@code pace} spends CPU before each
 * evaluation to keep the rate under the overflow, at the same cost on both sides of a comparison.
 * The worker thread also flushes, so with unique subjects a few percent of the events are still
 * dropped while it builds each flush's payloads.
 *
 * <p>Run: {@code ./gradlew :dd-openfeature:jmh -Pjmh.includes=ClientEvaluationBenchmark
 * -Pjmh.profilers=gc}.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 1)
public class ClientEvaluationBenchmark {
  private static final String DOMAIN = "client-evaluation-benchmark";

  /** More subjects than the exposure cache holds, so unique subjects never hit it. */
  private static final int SUBJECTS = 1 << 18;

  /** A case file of the scale suite, as in {@link ScaleSuiteBenchmark}. */
  @Param({"scale/flags", "scale/rules", "scale/splits", "scale/variations", "scale/wide-context"})
  public String cases;

  @Param({"repeated", "unique"})
  public String subjects;

  /** CPU spent before each evaluation, in {@link Blackhole#consumeCPU} tokens; 0 for none. */
  @Param({"0"})
  public int pace;

  /** The health metrics the runtime reported, such as events dropped on a full queue. */
  private static final Map<String, LongAdder> HEALTH = new ConcurrentHashMap<>();

  private static final Queue<Cursor> CURSORS = new ConcurrentLinkedQueue<>();

  private Client client;
  private ScaleCase[] scaleCases;
  private String[] subjectKeys;

  /** The position of one benchmark thread in the cases and subjects. */
  @State(Scope.Thread)
  public static class Cursor {
    private static final AtomicInteger THREADS = new AtomicInteger();

    private int nextCase;
    private int nextSubject;
    private long evaluations;
    private final SubjectContext context = new SubjectContext();

    /**
     * Threads that start at the same case and subject stay in lockstep, all evaluating the same
     * flag for the same subject at once. Start each thread at its own offset instead.
     */
    @Setup(Level.Trial)
    public void spread(final ClientEvaluationBenchmark benchmark) {
      final int thread = THREADS.getAndIncrement();
      nextCase = thread % benchmark.scaleCases.length;
      if (benchmark.subjectKeys != null) {
        nextSubject = offset(thread, benchmark.subjectKeys.length);
      }
      CURSORS.add(this);
    }

    /** Fibonacci hashing spreads consecutive thread numbers across the range. */
    private static int offset(final int thread, final int length) {
      return (int) (((thread * 0x9E3779B9L) & 0xFFFFFFFFL) * length >>> 32);
    }

    ScaleCase next(final ScaleCase[] scaleCases) {
      evaluations++;
      final ScaleCase scaleCase = scaleCases[nextCase];
      nextCase = nextCase + 1 == scaleCases.length ? 0 : nextCase + 1;
      return scaleCase;
    }

    EvaluationContext subject(final ScaleCase scaleCase, final String[] subjectKeys) {
      context.attributes = scaleCase.context();
      context.targetingKey = subjectKeys[nextSubject];
      nextSubject = nextSubject + 1 == subjectKeys.length ? 0 : nextSubject + 1;
      return context;
    }
  }

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    final int slash = cases.indexOf('/');
    final ScaleSuites.Suite suite = ScaleSuites.load(cases.substring(0, slash));
    final AgentBridge bridge = new BenchmarkBridge(suite.config());
    final DDEvaluator evaluator =
        new DDEvaluator(
            () -> {},
            bridge,
            TestSettings.of(bridge, "feature.flags.configuration.source", "remote_config"),
            ExperimentationOptOutMode.DISABLED);
    final Provider provider = new Provider(new Provider.Options(), evaluator, false);
    OpenFeatureAPI.getInstance().setProviderAndWait(DOMAIN, provider);
    client = OpenFeatureAPI.getInstance().getClient(DOMAIN);
    final List<ScaleCase> loaded = suite.cases(cases.substring(slash + 1));
    for (final ScaleCase scaleCase : loaded) {
      scaleCase.check(scaleCase.evaluate(provider));
      check(scaleCase.evaluate(client, scaleCase.context()));
    }
    scaleCases = loaded.toArray(new ScaleCase[0]);
    if ("unique".equals(subjects)) {
      subjectKeys = new String[SUBJECTS];
      for (int i = 0; i < SUBJECTS; i++) {
        subjectKeys[i] = "subject-" + i;
      }
    }
  }

  @TearDown(Level.Trial)
  @SuppressForbidden
  public void tearDown() {
    // The shutdown flushes, which reports the last of the health metrics.
    OpenFeatureAPI.getInstance().shutdown();
    long evaluations = 0;
    for (final Cursor cursor : CURSORS) {
      evaluations += cursor.evaluations;
    }
    final LongAdder dropped = HEALTH.get("flagevaluation.rows.dropped{queue_overflow}");
    final long drops = dropped == null ? 0 : dropped.sum();
    System.out.printf(
        Locale.ROOT,
        "%n[%s %s pace=%d] %,d of %,d flag evaluation events dropped on a full queue (%.1f%%);"
            + " health: %s%n",
        cases,
        subjects,
        pace,
        drops,
        evaluations,
        evaluations == 0 ? 0.0 : 100.0 * drops / evaluations,
        HEALTH);
  }

  @Benchmark
  public FlagEvaluationDetails<?> evaluate(final Cursor cursor) {
    if (pace > 0) {
      Blackhole.consumeCPU(pace);
    }
    final ScaleCase scaleCase = cursor.next(scaleCases);
    return scaleCase.evaluate(
        client, subjectKeys == null ? scaleCase.context() : cursor.subject(scaleCase, subjectKeys));
  }

  private static void check(final FlagEvaluationDetails<?> details) {
    if (details.getErrorCode() != null) {
      throw new IllegalStateException(
          "Unexpected error for " + details.getFlagKey() + ": " + details.getErrorCode());
    }
  }

  /**
   * A case context for another subject. The hooks copy what they keep before the evaluation
   * returns, so one instance per thread is reused.
   */
  static final class SubjectContext implements EvaluationContext {
    EvaluationContext attributes;
    String targetingKey;

    @Override
    public String getTargetingKey() {
      return targetingKey;
    }

    @Override
    public EvaluationContext merge(final EvaluationContext overridingContext) {
      return new MutableContext(targetingKey, attributes.asMap()).merge(overridingContext);
    }

    @Override
    public boolean isEmpty() {
      return false;
    }

    @Override
    public Set<String> keySet() {
      return attributes.keySet();
    }

    @Override
    public Value getValue(final String key) {
      return attributes.getValue(key);
    }

    @Override
    public Map<String, Value> asMap() {
      return attributes.asMap();
    }

    @Override
    public Map<String, Value> asUnmodifiableMap() {
      return attributes.asUnmodifiableMap();
    }

    @Override
    public Map<String, Object> asObjectMap() {
      return attributes.asObjectMap();
    }
  }

  /** Delivers one configuration through Remote Configuration and drops every event. */
  private static final class BenchmarkBridge implements AgentBridge {
    private final byte[] configuration;

    BenchmarkBridge(final byte[] configuration) {
      this.configuration = configuration;
    }

    @Override
    public String setting(final String key) {
      return null;
    }

    @Override
    public ConfigurationSource remoteConfiguration() {
      return (final Consumer<byte[]> listener) -> {
        listener.accept(configuration);
        return () -> {};
      };
    }

    @Override
    public EventTransport eventProxy() {
      return (route, json) -> {};
    }

    @Override
    public HealthMetrics healthMetrics() {
      return (metric, value, reason) ->
          HEALTH
              .computeIfAbsent(
                  reason == null ? metric : metric + "{" + reason + "}", k -> new LongAdder())
              .add(value);
    }

    @Override
    public SpanEnricher spanEnricher() {
      return SpanEnricher.NOOP;
    }

    @Override
    public ShutdownSource shutdown() {
      return null;
    }
  }
}
