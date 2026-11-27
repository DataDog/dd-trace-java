package datadog.trace.util;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.util.Arrays;
import java.util.Base64;
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
import org.openjdk.jmh.annotations.Warmup;

/**
 * Compares {@link Base64Decoding} against the pattern it replaces at call sites: {@link
 * Base64#getDecoder()} with a catch of {@link IllegalArgumentException} that returns {@code null}.
 *
 * <ul>
 *   <li><b>valid</b> — a 28-character value, the size of a typical pathway context.
 *   <li><b>validLarge</b> — 4 KB of encoded data, the size of a small message body.
 *   <li><b>badLength</b> — a value whose length and padding can't be valid Base64.
 *   <li><b>badChar</b> — a value with a character outside the Base64 alphabet. Both variants reach
 *       the JDK decoder here, so this case shows the cost of the exception on both sides.
 * </ul>
 *
 * <p>The cost of an exception grows with the stack it captures, so {@code depth} adds frames above
 * the decode, approximating where propagation code runs inside an instrumented request.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class Base64DecodingBenchmark {
  private static final Base64.Decoder DECODER = Base64.getDecoder();

  @Param({"valid", "validLarge", "badLength", "badChar"})
  String input;

  @Param({"0", "64"})
  int depth;

  byte[] src;

  @Setup
  public void setup() {
    switch (input) {
      case "valid":
        src = Base64.getEncoder().encode(new byte[20]);
        break;
      case "validLarge":
        src = Base64.getEncoder().encode(new byte[3072]);
        break;
      case "badLength":
        src = "AAAAAAAAAAAAAAAAAAAAAAAAAA=".getBytes(ISO_8859_1);
        break;
      case "badChar":
        src = Arrays.copyOf(Base64.getEncoder().encode(new byte[20]), 28);
        src[13] = '!';
        break;
      default:
        throw new IllegalArgumentException(input);
    }
  }

  @Benchmark
  public byte[] jdkWithCatch() {
    return jdkWithCatch(src, depth);
  }

  @Benchmark
  public byte[] base64Decoding() {
    return base64Decoding(src, depth);
  }

  private static byte[] jdkWithCatch(byte[] src, int depth) {
    if (depth > 0) {
      return jdkWithCatch(src, depth - 1);
    }
    try {
      return DECODER.decode(src);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static byte[] base64Decoding(byte[] src, int depth) {
    if (depth > 0) {
      return base64Decoding(src, depth - 1);
    }
    return Base64Decoding.decode(src);
  }
}
