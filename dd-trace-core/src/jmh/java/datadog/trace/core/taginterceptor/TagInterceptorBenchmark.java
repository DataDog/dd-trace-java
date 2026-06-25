package datadog.trace.core.taginterceptor;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmark for {@link TagInterceptor#needsIntercept(String)} -- the per-tag gate {@code
 * DDSpanContext} runs on every {@code setTag}. This is where the change lives: the old code
 * switched over ~22 {@code String} case labels (a {@code hashCode} switch + {@code equals}); the
 * new code does one open-addressed {@link datadog.trace.util.StringIndex} probe ({@code ==} fast
 * path), falling to the (usually empty) split-tags set only on a miss.
 *
 * <p>Two paths, both exercised: <b>miss</b> (the common case -- most tags aren't intercepted, so
 * the old switch fell all the way to {@code default}), and <b>hit</b> (an intercepted tag).
 * Read-only, so {@code @Threads(8)} is clean (no span, no store to dilute the signal). Tag keys are
 * interned literals -- the realistic case.
 *
 * <p>Run before (switch) vs after (StringIndex) by toggling TagInterceptor; {@code -prof gc} should
 * show ~0 B/op both ways (this proves <i>throughput</i>, not allocation).
 *
 * <pre>
 *   ./gradlew :dd-trace-core:jmh   # add -prof gc
 * </pre>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Threads(8)
@State(Scope.Benchmark)
public class TagInterceptorBenchmark {

  private final TagInterceptor interceptor = new TagInterceptor(new RuleFlags());

  // Intercepted (fixed) tags -- handlerId hits the FIXED index.
  static final String[] INTERCEPTED = {
    DDTags.RESOURCE_NAME,
    DDTags.SERVICE_NAME,
    Tags.DB_STATEMENT,
    Tags.HTTP_STATUS,
    Tags.ERROR,
    Tags.SPAN_KIND,
    Tags.HTTP_URL,
    Tags.SAMPLING_PRIORITY,
  };

  // Ordinary integration/app tags that are NOT intercepted -- the common miss path.
  static final String[] NOT_INTERCEPTED = {
    "http.useragent",
    "db.instance",
    "messaging.system",
    "component",
    "thread.name",
    "network.peer.address",
    "http.route",
    "rpc.method",
  };

  /** Per-thread cursors so threads don't contend on a shared index under {@code @Threads(8)}. */
  @State(Scope.Thread)
  public static class Cursor {
    int hit;
    int miss;
  }

  @Benchmark
  public boolean intercepted(Cursor cursor) {
    int i = cursor.hit;
    cursor.hit = (i + 1) % INTERCEPTED.length;
    return interceptor.needsIntercept(INTERCEPTED[i]);
  }

  @Benchmark
  public boolean notIntercepted(Cursor cursor) {
    int i = cursor.miss;
    cursor.miss = (i + 1) % NOT_INTERCEPTED.length;
    return interceptor.needsIntercept(NOT_INTERCEPTED[i]);
  }
}
