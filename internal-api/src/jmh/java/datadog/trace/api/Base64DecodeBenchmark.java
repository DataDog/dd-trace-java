package datadog.trace.api;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.api.Functions.GuardedBase64Decode;
import java.util.Base64;
import java.util.Random;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * {@link GuardedBase64Decode#decodeOrNull}, the exception-free decoder, against {@link
 * Base64#getDecoder()}. These are the two costs that set the guard's {@code closeAfter}: the
 * exception-free decoder's overhead on valid input (the cost of staying engaged), and the JDK
 * decoder's failure on invalid input (the cost of disengaging too early).
 *
 * <ul>
 *   <li>{@code size}: {@code header} is a trace-header-sized value, {@code long} is about 1.4 KB of
 *       Base64, where the JDK's block decoding, and any intrinsic for it, has room to pay off.
 *   <li>{@code depth}: the stack the JDK decoder's exception fills in; a consumer thread's stack is
 *       deeper than a benchmark thread's.
 * </ul>
 *
 * <p>Run with {@code ./gradlew :internal-api:jmh -Pjmh.includes=Base64DecodeBenchmark
 * -Pjmh.profilers=gc}, and with {@code -PtestJvm=17} for a newer JDK.
 *
 * <p>Results, one run each: Zulu 8.0.382 and Zulu 17.0.7 (HotSpot), MacBook M1, single thread, 2
 * forks of 5 one-second iterations, on a laptop with normal background activity. x86 is not
 * measured. ns/op is derived from ops/s; B/op is from {@code -prof gc}.
 *
 * <pre>
 * ns/op (B/op)                  JDK 8                  JDK 17
 *                          header      long       header      long
 * jdkValid,  depth 0       87.7 (160)  3631       53.4 (104)  1598
 * exceptionFreeValid       86.3 (160)  3904       80.3 (104)  3072
 * jdkInvalid, depth 0       889 (928)   926        921 (1024)  956
 * jdkInvalid, depth 50     2080 (1904) 2189       2498 (2384) 2512
 * exceptionFreeInvalid      6.4 (0)     6.4        6.2 (0)     6.3
 * </pre>
 *
 * On JDK 8 the exception-free decoder is about as fast as the JDK's on header-sized input, and
 * about 7% slower on long input. On JDK 17 the JDK's decoder, which decodes in blocks and has an
 * intrinsic on this platform, is about 27 ns faster on header-sized input and about twice as fast
 * on long input; that difference is why the guard switches between the two rather than always using
 * the exception-free one. Invalid input costs the exception-free decoder about 6 ns and no
 * allocation, at any length, where the JDK's costs about 0.9 us, or 2.1 to 2.5 us at depth 50.
 *
 * <p>The exception-free decoder allocates its output only once the first unit is clean. Against a
 * copy that allocated up front, in the same run, that cost about 7 ns (JDK 17) to 12 ns (JDK 8) on
 * valid header-sized input at depth 0, and nothing measurable at depth 50 or on long input, while
 * saving 2 to 4 ns and 40 B on invalid header-sized input and about 55 ns and 1 KB on invalid long
 * input. It runs only while the guard is engaged. The {@code jdk*} rows are from an earlier run of
 * the same day, the {@code exceptionFree*} rows from the later one.
 */
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Threads(1)
@State(Scope.Benchmark)
public class Base64DecodeBenchmark {

  @Param({"header", "long"})
  String size;

  @Param({"0", "50"})
  int depth;

  byte[] valid;
  byte[] invalid;

  @Setup
  public void setup() {
    byte[] data;
    if ("header".equals(size)) {
      data = "1234567890123456789".getBytes(UTF_8);
    } else {
      data = new byte[1024];
      new Random(12672).nextBytes(data);
    }
    valid = Base64.getEncoder().encode(data);
    // a plain-text value where Base64 was expected, bad from its fourth byte
    invalid = valid.clone();
    invalid[3] = '-';
    String expected = new String(data, UTF_8);
    if (!expected.equals(GuardedBase64Decode.decodeOrNull(valid))
        || !expected.equals(jdk(valid))
        || GuardedBase64Decode.decodeOrNull(invalid) != null
        || jdk(invalid) != null) {
      throw new IllegalStateException("the two decoders must agree on the benchmark inputs");
    }
  }

  static String jdk(byte[] src) {
    try {
      return new String(Base64.getDecoder().decode(src), UTF_8);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  @Benchmark
  public Object jdkValid() {
    return jdkAt(depth, valid);
  }

  @Benchmark
  public Object exceptionFreeValid() {
    return exceptionFreeAt(depth, valid);
  }

  @Benchmark
  public Object jdkInvalid() {
    return jdkAt(depth, invalid);
  }

  @Benchmark
  public Object exceptionFreeInvalid() {
    return exceptionFreeAt(depth, invalid);
  }

  private static Object jdkAt(int remaining, byte[] src) {
    return remaining > 0 ? jdkAt(remaining - 1, src) : jdk(src);
  }

  private static Object exceptionFreeAt(int remaining, byte[] src) {
    return remaining > 0
        ? exceptionFreeAt(remaining - 1, src)
        : GuardedBase64Decode.decodeOrNull(src);
  }
}
