package datadog.trace.api;

import static datadog.trace.api.Functions.BASE64_DECODE;

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
 * <p>It also measures a reusable form of the guard. {@link DynamicLatch} is a local sketch of the
 * shape discussed in APMLP-1884: an abstract class whose subclass <em>is</em> the strategy (an
 * optimistic parse, a cheap correct pre-check, and a stackless failure), held in a {@code static
 * final}. It replaces an earlier pair of generic variants that stored the strategy in a field or
 * took it at call time, which are not needed once the subclass carries the hooks. It has two public
 * flavors: {@link DynamicLatch#get} lets the failure flow to the caller (throwing a stackless
 * stand-in while engaged), and {@link DynamicLatch#tryGetOrNull} converts it to {@code null} and
 * never builds an exception at all while engaged.
 *
 * <p>The hand-written {@link Breaker} is the specialized baseline the latch is compared against.
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

  /**
   * Local sketch of a {@code DynamicLatch}: the subclass supplies the strategy, so there is no
   * separate handler object and no question of storing it in a field versus passing it per call.
   *
   * <p>Two states with hysteresis: closed (optimistic path) and engaged (guarded path). A failure
   * of the declared type engages it, and {@link #closeAfter()} consecutive successes disengage it.
   * It counts calls, not time, never rejects a call, and keeps plain racy state: a stale read costs
   * one more pre-check or one more exception, never a wrong result.
   *
   * <p>Three hooks, all cheap to state: {@link #parse}, {@link #isDefinitelyInvalid} and {@link
   * #stacklessFailure}. The last is needed only by {@link #get}; {@link #tryGetOrNull} converts the
   * failure to {@code null} and never builds an exception while engaged.
   *
   * @param <I> input type
   * @param <O> result type
   * @param <X> the failure this latch reacts to (unchecked here, to keep the sketch small)
   */
  abstract static class DynamicLatch<I, O, X extends RuntimeException> {
    private final Class<X> failureType;
    private int state;

    DynamicLatch(Class<X> failureType) {
      this.failureType = failureType;
    }

    /** The optimistic parse. May throw {@code X} for bad input. */
    abstract O parse(I input);

    /** A cheap, correct pre-check: true only if the input is definitely invalid. Never throws. */
    abstract boolean isDefinitelyInvalid(I input);

    /** A failure to throw while engaged. Must carry no stack trace, and must not be shared. */
    abstract X stacklessFailure(I input);

    /** How many consecutive successes disengage the latch. */
    int closeAfter() {
      return CLOSE_THRESHOLD;
    }

    /** Flow-through: the caller sees the failure, but while engaged it costs no stack trace. */
    final O get(I input) {
      if (state > 0 && isDefinitelyInvalid(input)) {
        throw stacklessFailure(input);
      }
      try {
        O result = parse(input);
        if (state > 0) {
          state--;
        }
        return result;
      } catch (RuntimeException e) {
        if (failureType.isInstance(e)) {
          state = closeAfter();
        }
        throw e;
      }
    }

    /** Converting: {@code null} for bad input. While engaged, no exception is built at all. */
    final O tryGetOrNull(I input) {
      if (state > 0 && isDefinitelyInvalid(input)) {
        return null;
      }
      try {
        O result = parse(input);
        if (state > 0) {
          state--;
        }
        return result;
      } catch (RuntimeException e) {
        if (!failureType.isInstance(e)) {
          throw e;
        }
        state = closeAfter();
        return null;
      }
    }
  }

  /** The Base64 strategy. A named final class, so a field of this type has an exact type. */
  static final class Base64Latch extends DynamicLatch<byte[], String, IllegalArgumentException> {
    Base64Latch() {
      super(IllegalArgumentException.class);
    }

    @Override
    String parse(byte[] input) {
      return new String(Base64.getDecoder().decode(input), StandardCharsets.UTF_8);
    }

    @Override
    boolean isDefinitelyInvalid(byte[] input) {
      return !looksLikeBase64(input);
    }

    @Override
    IllegalArgumentException stacklessFailure(byte[] input) {
      return new FastFailBase64Exception();
    }
  }

  final Breaker breakerForValid = new Breaker();
  final Breaker breakerForInvalid = new Breaker();
  final Breaker breakerThrowingForValid = new Breaker();
  final Breaker breakerThrowingForInvalid = new Breaker();

  // One latch per arm, each a static final of the exact type, as a call site would hold it.
  static final Base64Latch LATCH_FLOW_VALID = new Base64Latch();
  static final Base64Latch LATCH_FLOW_INVALID = new Base64Latch();
  static final Base64Latch LATCH_CONVERT_VALID = new Base64Latch();
  static final Base64Latch LATCH_CONVERT_INVALID = new Base64Latch();
  static final Base64Latch LATCH_FLOW_MIX = new Base64Latch();
  static final Base64Latch LATCH_CONVERT_MIX = new Base64Latch();

  /** Fails fast if the sketch does not behave as the benchmark assumes. */
  @Setup
  public void checkTheSketchBehaves() {
    Base64Latch latch = new Base64Latch();
    String expected = new String(Base64.getDecoder().decode(VALID), StandardCharsets.UTF_8);
    if (!expected.equals(latch.get(VALID)) || !expected.equals(latch.tryGetOrNull(VALID))) {
      throw new IllegalStateException("a valid value must decode");
    }
    if (latch.tryGetOrNull(INVALID) != null) {
      throw new IllegalStateException("bad input must convert to null");
    }
    // the failure above engaged it: the flow-through flavor must now fail without a stack trace
    try {
      latch.get(INVALID);
      throw new IllegalStateException("bad input must throw");
    } catch (FastFailBase64Exception e) {
      if (e.getStackTrace().length != 0) {
        throw new IllegalStateException("the stand-in must carry no stack trace");
      }
    }
    if (!expected.equals(latch.get(VALID))) {
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
  public void latchFlowValid(Blackhole bh) {
    bh.consume(LATCH_FLOW_VALID.get(VALID));
  }

  @Benchmark
  public void latchFlowInvalid(Blackhole bh) {
    try {
      bh.consume(LATCH_FLOW_INVALID.get(INVALID));
    } catch (IllegalArgumentException e) {
      bh.consume(e);
    }
  }

  @Benchmark
  public void latchConvertValid(Blackhole bh) {
    bh.consume(LATCH_CONVERT_VALID.tryGetOrNull(VALID));
  }

  @Benchmark
  public void latchConvertInvalid(Blackhole bh) {
    bh.consume(LATCH_CONVERT_INVALID.tryGetOrNull(INVALID));
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
  public void mixLatchFlow(Mix mix, Blackhole bh) {
    byte[] bytes = mix.next();
    try {
      bh.consume(LATCH_FLOW_MIX.get(bytes));
    } catch (IllegalArgumentException e) {
      bh.consume(e);
    }
  }

  @Benchmark
  public void mixLatchConvert(Mix mix, Blackhole bh) {
    bh.consume(LATCH_CONVERT_MIX.tryGetOrNull(mix.next()));
  }
}
