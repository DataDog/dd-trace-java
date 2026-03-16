package datadog.trace.util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Supplier;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * <ul>
 * <li>(RECOMMENDED) AtomicFieldUpdater - especially, when containing object is frequently constructed
 * <li>Atomic - usually, performs similarly to AtomicFieldUpdater - worse inside commonly constructed objects
 * </ul>
 * 
 * Instead of introucing an Atomic field into a class, a volatile field with an AtomicFieldUpdater is preferred when possible.
 * <ul>Types with AtomicFieldUpdaters are...
 * <li>int
 * <li>long
 * <li>reference (e.g. Object types)
 * </ul>
 * 
 * While the performance of Atomic is on par with AtomicFieldUpdater (and sometimes slightly better) inside a frequently 
 * constructed object, the impact of the extra allocation on garbage collection is detrimental to the application as a whole.
 * 
 * <code> Java 17 - MacBook M1 - 8 threads
 * Benchmark                                                                Mode  Cnt           Score           Error   Units
 * AtomicsBenchmark.atomicFieldUpdater_construction                        thrpt    6  4442811623.924 ± 293568588.782   ops/s
 * AtomicsBenchmark.atomicFieldUpdater_construction:gc.alloc.rate.norm     thrpt    6          16.000 ±         0.001    B/op

 * AtomicsBenchmark.atomicFieldUpdater_get                                 thrpt    6  1849238178.083 ±  29719143.488   ops/s
 * AtomicsBenchmark.atomicFieldUpdater_get:gc.alloc.rate.norm              thrpt    6          ≈ 10⁻⁶                    B/op

 * AtomicsBenchmark.atomicFieldUpdater_getVolatile                         thrpt    6  1814698391.230 ±  54378746.492   ops/s
 * AtomicsBenchmark.atomicFieldUpdater_getVolatile:gc.alloc.rate.norm      thrpt    6          ≈ 10⁻⁶                    B/op

 * AtomicsBenchmark.atomicFieldUpdater_incrementAndGet                     thrpt    6    15871753.651 ±    432451.444   ops/s
 * AtomicsBenchmark.atomicFieldUpdater_incrementAndGet:gc.alloc.rate.norm  thrpt    6          ≈ 10⁻⁴                    B/op

 * AtomicsBenchmark.atomic_construction                                    thrpt    6  2532095943.171 ±  87644803.894   ops/s
 * AtomicsBenchmark.atomic_construction:gc.alloc.rate.norm                 thrpt    6          32.000 ±         0.001    B/op

 * AtomicsBenchmark.atomic_incrementAndGet                                 thrpt    6    16416635.546 ±    751137.083   ops/s
 * AtomicsBenchmark.atomic_incrementAndGet:gc.alloc.rate.norm              thrpt    6          ≈ 10⁻⁴                    B/op

 * AtomicsBenchmark.atomic_read                                            thrpt    6  1943297600.062 ±  43802387.143   ops/s
 * AtomicsBenchmark.atomic_read:gc.alloc.rate.norm                         thrpt    6          ≈ 10⁻⁶                    B/op
 */
@Fork(2)
@Warmup(iterations=2)
@Measurement(iterations=3)
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
	static final AtomicIntegerFieldUpdater<FieldHolder> AFU_FIELD = AtomicIntegerFieldUpdater.newUpdater(FieldHolder.class, "field");
	
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
  
  static final AtomicHolder[] atomicHolders = init(() -> {
	AtomicHolder[] holders = new AtomicHolder[SIZE];
    for ( int i = 0; i < holders.length; ++i ) {
      holders[i] = new AtomicHolder(i * 2);
    }
    return holders;
  });
  
  static final FieldHolder[] fieldHolders = init(() -> {
	  FieldHolder[] holders = new FieldHolder[SIZE];
    for ( int i = 0; i < holders.length; ++i ) {
      holders[i] = new FieldHolder(i * 2);
    }
    return holders;
  });
  
  static final <T> T init(Supplier<T> supplier) {
	return supplier.get();
  }
  
  static int sharedLookupIndex = 0;
  
  static <T> T next(T[] holders) {
	int localIndex = ++sharedLookupIndex;
	if ( localIndex >= holders.length ) {
	  sharedLookupIndex = localIndex = 0;
	}
	return holders[localIndex];
  }
  
  @Benchmark
  public Object atomic_construction() {
	return new AtomicHolder(0);
  }
  
  @Benchmark
  public int atomic_incrementAndGet() {
	return next(atomicHolders).incrementAndGet();  
  }
  
  @Benchmark
  public Object atomic_read() {
	return next(atomicHolders).get();
  }
  
  @Benchmark
  public Object atomicFieldUpdater_construction() {
	return new FieldHolder(0);
  }
  
  @Benchmark
  public Object atomicFieldUpdater_getVolatile() {
	return next(fieldHolders).getVolatile();
  }
  
  @Benchmark
  public Object atomicFieldUpdater_get() {
	return next(fieldHolders).get();
  }
  
  @Benchmark
  public int atomicFieldUpdater_incrementAndGet() {
	return next(fieldHolders).incrementAndGet();
  }
}
