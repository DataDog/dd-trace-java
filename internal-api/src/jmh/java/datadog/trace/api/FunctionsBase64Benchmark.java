package datadog.trace.api;

import static datadog.trace.api.Functions.BASE64_DECODE;

import datadog.trace.api.Functions.GuardedBase64Decode;
import datadog.trace.util.AdaptiveLatch;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Function;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Compares {@link Functions#BASE64_DECODE} on valid input (no exception) against malformed input,
 * where every call throws and catches an {@link IllegalArgumentException}. Motivated by a customer
 * seeing ~180K/day of this exact throw from Kafka header extraction (non-Base64 header values from
 * a mixed producer). Point is to see whether HotSpot's fast-throw stack-trace omission actually
 * kicks in for this call site under sustained repeated throws, or whether the caught path pays for
 * a full stack trace fill-in every time.
 *
 * <p>It also measures the reusable form of the guard, {@link AdaptiveLatch} (APMLP-1884): an
 * abstract class whose subclass <em>is</em> the strategy (an optimistic parse and a cheap, correct
 * pre-check), held in a {@code static final}. The arms use the production subclass, {@link
 * Functions.GuardedBase64Decode}. {@link AdaptiveLatch#tryApply} converts a failure to {@code null}
 * and never builds an exception at all while engaged.
 *
 * <p>The numbers below were measured on an earlier, benchmark-local sketch of the latch, then named
 * {@code DynamicLatch}, with {@code tryGetOrNull} for {@code tryApply}, before {@code fallback} was
 * added and before the pre-check stopped rejecting unpadded input. The sketch also had a
 * flow-through flavor, {@code get}, which threw a stackless stand-in while engaged; it was dropped
 * when the latch moved into production because no caller needs it, and its arms were removed. The
 * hand-written throwing {@link Breaker} still measures that shape. Not re-measured since.
 *
 * <p>The hand-written {@link Breaker} is the specialized baseline the latch is compared against.
 *
 * <p>Results, one run: Zulu 17.0.7 (HotSpot), MacBook M1, single thread, 5 forks, on a laptop with
 * normal background activity (load about 4 to 7). JDK 8 and x86 are not measured. Error margins are
 * below 4%.
 *
 * <pre>
 * ns/op                                valid input  invalid input
 * unguarded (status quo)                      30.5          905.7
 * always pre-check                            71.3           2.75
 * hand-written Breaker, converting            31.2           2.73
 * hand-written Breaker, throwing              31.4           11.6
 * sketch, converting (tryApply)               31.1           2.78
 * sketch, flow-through (get)                  31.2           11.5
 *
 * Mix, ns/op, one invalid input in every N
 *      N  unguarded  pre-check  Breaker  throwing  latch.get      tryApply
 *      2      467.1       36.6     47.7      49.9       51.9          47.3
 *     10      118.4       64.9     71.0      71.3       72.1          70.0
 *    100       41.9       70.0     46.1      46.4       47.9          45.9
 *   1000       41.6       70.3     44.7      45.7       45.1          45.6
 *  10000       34.3       70.2     36.2      35.3       39.6          35.5
 * </pre>
 *
 * The sketch matches the hand-written breaker: within 0.2 ns in the single-input arms, and within
 * about 4 ns in the mixed ones (the largest gap is flow-through at one in 10,000, 39.6 ns against
 * 35.3 ns). While engaged, the converting flavor costs about 2.8 ns where the status quo costs
 * about 906 ns; the flow-through flavor costs about 11.5 ns, the price of building a stackless
 * exception. Always pre-checking more than doubles the cost of valid input (71 ns against 30 ns),
 * which is what the adaptive form avoids.
 *
 * <p>It is a tradeoff, not a free win. With one invalid input in 100 or rarer, the guard costs 1 to
 * 4 ns over doing nothing and is about 24 to 34 ns cheaper than always pre-checking. With a high
 * rate (one in 2 or one in 10), always pre-checking is faster than the adaptive guard (36.6 ns
 * against about 47 to 52 ns at one in 2, and 65 ns against about 70 to 72 ns at one in 10). The
 * cause was not investigated.
 */
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class FunctionsBase64Benchmark {

  static final byte[] VALID =
      Base64.getEncoder().encode("x-datadog-trace-id=1234567890".getBytes(StandardCharsets.UTF_8));

  static final byte[] INVALID = "not-valid-base64!@#".getBytes(StandardCharsets.UTF_8);

  private static final boolean[] BASE64_ALPHABET = buildAlphabetTable();

  private static boolean[] buildAlphabetTable() {
    boolean[] table = new boolean[256];
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
    for (int i = 0; i < alphabet.length(); i++) {
      table[alphabet.charAt(i)] = true;
    }
    return table;
  }

  // Branch-free per byte on purpose: a data-dependent early exit helps the rare "bad byte early"
  // case but hurts the common valid case, which always scans the whole buffer anyway.
  private static boolean looksLikeBase64(byte[] bytes) {
    if (bytes.length == 0 || (bytes.length & 3) != 0) {
      return false;
    }
    boolean valid = true;
    for (byte b : bytes) {
      valid &= BASE64_ALPHABET[b & 0xFF];
    }
    return valid;
  }

  static final Function<byte[], String> PRECHECK_BASE64_DECODE =
      bytes -> looksLikeBase64(bytes) ? BASE64_DECODE.apply(bytes) : null;

  private static final int CLOSE_THRESHOLD = 20;

  /**
   * Thrown only when the cheap alphabet-scan precheck already knows the input can't be valid
   * Base64, so we never call into {@link Base64}'s decoder (or pay for its exception's stack-trace
   * capture) at all. Deliberately extends {@link IllegalArgumentException} so it's a drop-in for
   * existing catch sites around the real decoder; callers should not rely on a stack trace being
   * present for this specific instance — a real decode failure still throws the JDK's own
   * exception, unmodified, with its own message and stack trace.
   *
   * <p>A new instance is built per failure, never shared, so suppression cannot accumulate on it. A
   * shared instance would also have to disable suppression and forbid {@code initCause}.
   */
  static final class FastFailBase64Exception extends IllegalArgumentException {
    private static final String MESSAGE = "Header value is not valid Base64";

    FastFailBase64Exception() {
      super(MESSAGE);
    }

    // IllegalArgumentException has no writableStackTrace-suppressing constructor of its own, so
    // skip the stack walk here instead.
    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  }

  // Single-word countdown: 0 == closed (no precheck), >0 == guarded, counting down to close.
  // Plain int on purpose: this is advisory hysteresis, not correctness-critical state, so a lost
  // update or a stale read across threads just means one extra precheck or one extra exception.
  static final class Breaker {
    int state;

    String decode(byte[] bytes) {
      if (state > 0 && !looksLikeBase64(bytes)) {
        return null;
      }
      String result = BASE64_DECODE.apply(bytes);
      if (result == null) {
        state = CLOSE_THRESHOLD;
      } else if (state > 0) {
        state--;
      }
      return result;
    }

    // Same hysteresis, but preserves throw-based failure semantics: a precheck-known failure
    // throws our stack-trace-free stand-in, a real decode failure lets the JDK's own
    // IllegalArgumentException (with its own message and stack trace) propagate untouched.
    String decodeOrThrow(byte[] bytes) {
      if (state > 0 && !looksLikeBase64(bytes)) {
        throw new FastFailBase64Exception();
      }
      try {
        String result = new String(Base64.getDecoder().decode(bytes), StandardCharsets.UTF_8);
        if (state > 0) {
          state--;
        }
        return result;
      } catch (IllegalArgumentException e) {
        state = CLOSE_THRESHOLD;
        throw e;
      }
    }
  }

  final Breaker breakerForValid = new Breaker();
  final Breaker breakerForInvalid = new Breaker();
  final Breaker breakerThrowingForValid = new Breaker();
  final Breaker breakerThrowingForInvalid = new Breaker();

  // One latch per arm, each a static final of the exact type, as a call site would hold it.
  static final GuardedBase64Decode LATCH_CONVERT_VALID = new GuardedBase64Decode();
  static final GuardedBase64Decode LATCH_CONVERT_INVALID = new GuardedBase64Decode();
  static final GuardedBase64Decode LATCH_CONVERT_MIX = new GuardedBase64Decode();

  /** Fails fast if the latch does not behave as the benchmark assumes. */
  @Setup
  public void checkTheLatchBehaves() {
    GuardedBase64Decode latch = new GuardedBase64Decode();
    String expected = new String(Base64.getDecoder().decode(VALID), StandardCharsets.UTF_8);
    if (!expected.equals(latch.tryApply(VALID))) {
      throw new IllegalStateException("a valid value must decode");
    }
    if (latch.tryApply(INVALID) != null || !latch.isEngaged()) {
      throw new IllegalStateException("bad input must convert to null and engage the latch");
    }
    if (!expected.equals(latch.tryApply(VALID))) {
      throw new IllegalStateException("good input must still decode while engaged");
    }
  }

  @Benchmark
  public void breakerValid(Blackhole bh) {
    bh.consume(breakerForValid.decode(VALID));
  }

  @Benchmark
  public void breakerInvalid(Blackhole bh) {
    bh.consume(breakerForInvalid.decode(INVALID));
  }

  @Benchmark
  public void breakerThrowingValid(Blackhole bh) {
    bh.consume(breakerThrowingForValid.decodeOrThrow(VALID));
  }

  @Benchmark
  public void breakerThrowingInvalid(Blackhole bh) {
    try {
      bh.consume(breakerThrowingForInvalid.decodeOrThrow(INVALID));
    } catch (IllegalArgumentException e) {
      bh.consume(e);
    }
  }

  @Benchmark
  public void latchConvertValid(Blackhole bh) {
    bh.consume(LATCH_CONVERT_VALID.tryApply(VALID));
  }

  @Benchmark
  public void latchConvertInvalid(Blackhole bh) {
    bh.consume(LATCH_CONVERT_INVALID.tryApply(INVALID));
  }

  @Benchmark
  public void valid(Blackhole bh) {
    bh.consume(BASE64_DECODE.apply(VALID));
  }

  @Benchmark
  public void invalid(Blackhole bh) {
    bh.consume(BASE64_DECODE.apply(INVALID));
  }

  @Benchmark
  public void precheckValid(Blackhole bh) {
    bh.consume(PRECHECK_BASE64_DECODE.apply(VALID));
  }

  @Benchmark
  public void precheckInvalid(Blackhole bh) {
    bh.consume(PRECHECK_BASE64_DECODE.apply(INVALID));
  }

  // Deterministic stream: one INVALID every invalidEveryN calls, VALID otherwise. Each mix
  // benchmark gets its own counter and state so the strategies see the identical pattern.
  @State(Scope.Thread)
  public static class Mix {

    @Param({"2", "10", "100", "1000", "10000"})
    int invalidEveryN;

    int counter;
    final Breaker breaker = new Breaker();
    final Breaker throwingBreaker = new Breaker();

    byte[] next() {
      if (++counter >= invalidEveryN) {
        counter = 0;
        return INVALID;
      }
      return VALID;
    }
  }

  @Benchmark
  public void mixUnguarded(Mix mix, Blackhole bh) {
    bh.consume(BASE64_DECODE.apply(mix.next()));
  }

  @Benchmark
  public void mixGuarded(Mix mix, Blackhole bh) {
    bh.consume(PRECHECK_BASE64_DECODE.apply(mix.next()));
  }

  @Benchmark
  public void mixBreaker(Mix mix, Blackhole bh) {
    bh.consume(mix.breaker.decode(mix.next()));
  }

  @Benchmark
  public void mixBreakerThrowing(Mix mix, Blackhole bh) {
    byte[] bytes = mix.next();
    try {
      bh.consume(mix.throwingBreaker.decodeOrThrow(bytes));
    } catch (IllegalArgumentException e) {
      bh.consume(e);
    }
  }

  @Benchmark
  public void mixLatchConvert(Mix mix, Blackhole bh) {
    bh.consume(LATCH_CONVERT_MIX.tryApply(mix.next()));
  }
}
