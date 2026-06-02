package datadog.trace.api;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression test for the {@code DDTraceId} &lt;-&gt; {@code DD64bTraceId} class-initialization
 * deadlock.
 *
 * <p>{@code DD64bTraceId} is a subclass of {@code DDTraceId}, so the JVM must initialize {@code
 * DDTraceId} before {@code DD64bTraceId}. The bug was that {@code DDTraceId.<clinit>} in turn
 * initialized {@code DD64bTraceId} by building its {@code ZERO}/{@code ONE} constants via {@code
 * DD64bTraceId.from(...)}. When the two classes were first touched concurrently from opposite ends
 * (one thread initializing {@code DDTraceId}, another initializing {@code DD64bTraceId}), each
 * thread held one class-initialization lock and waited for the other, hanging trace creation. This
 * surfaced as 30s {@code LogInjectionSmokeTest} timeouts in CI.
 *
 * <p>We now eagerly initialize {@code DDTraceId.ZERO} in {@code IdGenerationStrategy} - this class
 * is touched very early on in config before the tracer is installed, which is enough to break the
 * original cycle. An alternative fix would have been to introduce another subclass just for these
 * constants, but that has wide repercussions across the codebase. Furthermore, if that new class
 * was ever touched early on then a similar clinit deadlock could still occur. The only way to fix
 * this without breaking API compatibility is therefore to arrange for {@code DDTraceId.ZERO} to be
 * accessed as early as possible when configuring the trace id strategy.
 *
 * <p>This test initializes the two classes for the first time concurrently from opposite ends after
 * first touching {@code IdGenerationStrategy} and asserts neither thread hangs.
 *
 * <p>Runs forked ({@code forkEvery = 1}) so it gets a fresh JVM in which these classes have not yet
 * been initialized by another test. Without the fix it deadlocks and fails via the join check (and
 * the {@code @Timeout} backstop); with the fix it completes immediately.
 */
class DDTraceIdClinitDeadlockForkedTest {

  @Test
  @Timeout(value = 60, unit = SECONDS) // backstop; the join below is the primary guard
  void traceIdClassPairInitializesConcurrentlyWithoutDeadlock() throws Exception {
    final ClassLoader cl = getClass().getClassLoader();
    final CyclicBarrier barrier = new CyclicBarrier(2);
    final AtomicReference<Throwable> error = new AtomicReference<>();

    // One thread enters via the superclass (mirrors blackholeSpan() -> DDTraceId.ZERO), the other
    // via the subclass (mirrors IdGenerationStrategy.generateTraceId() -> DD64bTraceId.from()).

    Thread viaSuper =
        new Thread(
            () -> {
              try {
                barrier.await();
                Class.forName("datadog.trace.api.DDTraceId", true, cl);
              } catch (Throwable t) {
                error.compareAndSet(null, t);
              }
            },
            "init-DDTraceId");
    Thread viaSub =
        new Thread(
            () -> {
              try {
                barrier.await();
                Class.forName("datadog.trace.api.IdGenerationStrategy", true, cl);
                Class.forName("datadog.trace.api.DD64bTraceId", true, cl);
              } catch (Throwable t) {
                error.compareAndSet(null, t);
              }
            },
            "init-DD64bTraceId");
    // Daemon so a deadlock cannot block forked-JVM shutdown.
    viaSuper.setDaemon(true);
    viaSub.setDaemon(true);

    viaSuper.start();
    viaSub.start();
    viaSuper.join(SECONDS.toMillis(15));
    viaSub.join(SECONDS.toMillis(15));

    if (viaSuper.isAlive() || viaSub.isAlive()) {
      fail(
          "DDTraceId/DD64bTraceId class-initialization deadlock: DDTraceId.<clinit> must not "
              + "reference DD64bTraceId (init-DDTraceId.alive="
              + viaSuper.isAlive()
              + ", init-DD64bTraceId.alive="
              + viaSub.isAlive()
              + ").");
    }
    if (error.get() != null) {
      throw new AssertionError(
          "Unexpected error during concurrent class initialization", error.get());
    }
  }
}
