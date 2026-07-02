package datadog.trace.util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The choice between {@link AtomicInteger} and {@link AtomicIntegerFieldUpdater} depends on the
 * access pattern:
 *
 * <ul>
 * <li><b>Frequently constructed objects</b>: prefer AtomicFieldUpdater. It saves 16 B/op at
 *     construction (one fewer object) — the GC impact of that allocation compounds over the
 *     lifetime of the application.
 * <li><b>Long-lived objects with heavy incrementAndGet use</b>: AtomicInteger is ~33% faster for
 *     incrementAndGet (121M vs 91M ops/s). AtomicIntegerFieldUpdater carries overhead from its
 *     reflective field-access path that C2 cannot intrinsify as cleanly.
 * <li><b>Read-heavy paths</b>: essentially a wash (both ~2 B ops/s).
 * </ul>
 *
 * AtomicFieldUpdater supports {@code int}, {@code long}, and reference types.
 *
 * <p><b>Future:</b> {@code VarHandle} (Java 9+) is the modern replacement for
 * AtomicIntegerFieldUpdater. It avoids the reflective field-access overhead, which should close
 * the incrementAndGet gap with AtomicInteger while retaining the construction allocation advantage.
 * Not available here because internal-api targets Java 8.
 *
 * <code> Java 17 - MacBook M1 Pro Max - 8 threads
 * Benchmark                                                                Mode  Cnt           Score           Error   Units
 * AtomicsBenchmark.atomicFieldUpdater_construction                        thrpt    6  2215272588.708 ±  88556141.052   ops/s
 * AtomicsBenchmark.atomicFieldUpdater_construction:gc.alloc.rate.norm     thrpt    6          16.000 ±         0.001    B/op
 *
 * AtomicsBenchmark.atomicFieldUpdater_get                                 thrpt    6  2174739788.040 ±  56980971.014   ops/s
 * AtomicsBenchmark.atomicFieldUpdater_get:gc.alloc.rate.norm              thrpt    6          ≈ 10⁻⁶                    B/op
 *
 * AtomicsBenchmark.atomicFieldUpdater_getVolatile                         thrpt    6  2157331061.707 ± 136900932.336   ops/s
 * AtomicsBenchmark.atomicFieldUpdater_getVolatile:gc.alloc.rate.norm      thrpt    6          ≈ 10⁻⁶                    B/op
 *
 * AtomicsBenchmark.atomicFieldUpdater_incrementAndGet                     thrpt    6    90785783.320 ±   7650837.727   ops/s
 * AtomicsBenchmark.atomicFieldUpdater_incrementAndGet:gc.alloc.rate.norm  thrpt    6          ≈ 10⁻⁴                    B/op
 *
 * AtomicsBenchmark.atomic_construction                                    thrpt    6  1872153219.594 ±  83252749.463   ops/s
 * AtomicsBenchmark.atomic_construction:gc.alloc.rate.norm                 thrpt    6          32.000 ±         0.001    B/op
 *
 * AtomicsBenchmark.atomic_incrementAndGet                                 thrpt    6   120835704.294 ±  23025991.947   ops/s
 * AtomicsBenchmark.atomic_incrementAndGet:gc.alloc.rate.norm              thrpt    6          ≈ 10⁻⁴                    B/op
 *
 * AtomicsBenchmark.atomic_read                                            thrpt    6  1968266961.596 ±  57765039.412   ops/s
 * AtomicsBenchmark.atomic_read:gc.alloc.rate.norm                         thrpt    6          ≈ 10⁻⁶                    B/op
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
public class AtomicsBenchmark {
  static int SIZE = 32;

  static final class AtomicHolder {
    final AtomicInteger atomic;

    AtomicHolder(int num) {
      this.atomic = new AtomicInteger(num);
    }

    int get() {
      return this.atomic.get();
    }

    int incrementAndGet() {
      return this.atomic.incrementAndGet();
    }
  }

  static final class FieldHolder {
    static final AtomicIntegerFieldUpdater<FieldHolder> AFU_FIELD =
        AtomicIntegerFieldUpdater.newUpdater(FieldHolder.class, "field");

    volatile int field;

    FieldHolder(int num) {
      this.field = num;
    }

    int getVolatile() {
      return this.field;
    }

    int get() {
      return AFU_FIELD.get(this);
    }

    int incrementAndGet() {
      return AFU_FIELD.incrementAndGet(this);
    }
  }

  static final AtomicHolder[] atomicHolders =
      init(
          () -> {
            AtomicHolder[] holders = new AtomicHolder[SIZE];
            for (int i = 0; i < holders.length; ++i) {
              holders[i] = new AtomicHolder(i * 2);
            }
            return holders;
          });

  static final FieldHolder[] fieldHolders =
      init(
          () -> {
            FieldHolder[] holders = new FieldHolder[SIZE];
            for (int i = 0; i < holders.length; ++i) {
              holders[i] = new FieldHolder(i * 2);
            }
            return holders;
          });

  static final <T> T init(Supplier<T> supplier) {
    return supplier.get();
  }

  @State(Scope.Thread)
  public static class BenchmarkState {
    int index = 0;

    <T> T next(T[] holders) {
      if (++index >= holders.length) index = 0;
      return holders[index];
    }
  }

  @Benchmark
  public Object atomic_construction() {
    return new AtomicHolder(0);
  }

  @Benchmark
  public int atomic_incrementAndGet(BenchmarkState state) {
    return state.next(atomicHolders).incrementAndGet();
  }

  @Benchmark
  public Object atomic_read(BenchmarkState state) {
    return state.next(atomicHolders).get();
  }

  @Benchmark
  public Object atomicFieldUpdater_construction() {
    return new FieldHolder(0);
  }

  @Benchmark
  public Object atomicFieldUpdater_getVolatile(BenchmarkState state) {
    return state.next(fieldHolders).getVolatile();
  }

  @Benchmark
  public Object atomicFieldUpdater_get(BenchmarkState state) {
    return state.next(fieldHolders).get();
  }

  @Benchmark
  public int atomicFieldUpdater_incrementAndGet(BenchmarkState state) {
    return state.next(fieldHolders).incrementAndGet();
  }
}
