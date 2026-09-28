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

  /**
   * Hand-written specialization above vs. a generic {@code ParseHandler}/{@code GenericBreaker}
   * pair below, to see what the CHA-based devirtualization actually costs relative to a single
   * concrete, non-generic class with everything inlined by hand.
   */
  abstract static class ParseHandler<TInput, TOutput, TException extends RuntimeException> {
    abstract boolean isDefinitelyInvalid(TInput input);

    abstract TOutput parse(TInput input);

    abstract TException createFastFailException();
  }

  static final ParseHandler<byte[], String, IllegalArgumentException> BASE64_PARSE_HANDLER =
      new ParseHandler<byte[], String, IllegalArgumentException>() {
        @Override
        boolean isDefinitelyInvalid(byte[] input) {
          return !looksLikeBase64(input);
        }

        @Override
        String parse(byte[] input) {
          return new String(Base64.getDecoder().decode(input), StandardCharsets.UTF_8);
        }

        @Override
        IllegalArgumentException createFastFailException() {
          return new FastFailBase64Exception();
        }
      };

  // Strategy composed at construction time (stored as a field). Contrasted below with
  // GenericBreakerParam, which takes the same strategy at call time instead — comparing them is
  // the actual point, since a field forces C2 to also prove the receiver is constant before it
  // can fold through to a concrete ParseHandler, while a call-time argument doesn't.
  static final class GenericBreakerCtor<TInput, TOutput, TException extends RuntimeException> {
    private final ParseHandler<TInput, TOutput, TException> handler;
    int state;

    GenericBreakerCtor(ParseHandler<TInput, TOutput, TException> handler) {
      this.handler = handler;
    }

    TOutput decode(TInput input) {
      if (state > 0 && handler.isDefinitelyInvalid(input)) {
        throw handler.createFastFailException();
      }
      try {
        TOutput result = handler.parse(input);
        if (state > 0) {
          state--;
        }
        return result;
      } catch (RuntimeException e) {
        state = CLOSE_THRESHOLD;
        throw e;
      }
    }
  }

  // Same hysteresis, but the strategy is passed at call time rather than stored in a field.
  static final class GenericBreakerParam<TInput, TOutput, TException extends RuntimeException> {
    int state;

    TOutput decode(TInput input, ParseHandler<TInput, TOutput, TException> handler) {
      if (state > 0 && handler.isDefinitelyInvalid(input)) {
        throw handler.createFastFailException();
      }
      try {
        TOutput result = handler.parse(input);
        if (state > 0) {
          state--;
        }
        return result;
      } catch (RuntimeException e) {
        state = CLOSE_THRESHOLD;
        throw e;
      }
    }
  }

  final GenericBreakerCtor<byte[], String, IllegalArgumentException> genericBreakerForValid =
      new GenericBreakerCtor<>(BASE64_PARSE_HANDLER);
  final GenericBreakerCtor<byte[], String, IllegalArgumentException> genericBreakerForInvalid =
      new GenericBreakerCtor<>(BASE64_PARSE_HANDLER);

  final GenericBreakerParam<byte[], String, IllegalArgumentException> genericBreakerParamForValid =
      new GenericBreakerParam<>();
  final GenericBreakerParam<byte[], String, IllegalArgumentException>
      genericBreakerParamForInvalid = new GenericBreakerParam<>();

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
  public void genericBreakerValid(Blackhole bh) {
    bh.consume(genericBreakerForValid.decode(VALID));
  }

  @Benchmark
  public void genericBreakerInvalid(Blackhole bh) {
    try {
      bh.consume(genericBreakerForInvalid.decode(INVALID));
    } catch (IllegalArgumentException e) {
      bh.consume(e);
    }
  }

  @Benchmark
  public void genericBreakerParamValid(Blackhole bh) {
    bh.consume(genericBreakerParamForValid.decode(VALID, BASE64_PARSE_HANDLER));
  }

  @Benchmark
  public void genericBreakerParamInvalid(Blackhole bh) {
    try {
      bh.consume(genericBreakerParamForInvalid.decode(INVALID, BASE64_PARSE_HANDLER));
    } catch (IllegalArgumentException e) {
      bh.consume(e);
    }
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
  // benchmark gets its own counter/breaker so the three strategies see the identical pattern.
  @State(Scope.Thread)
  public static class Mix {

    @Param({"2", "10", "100", "1000", "10000"})
    int invalidEveryN;

    int counter;
    final Breaker breaker = new Breaker();
    final Breaker throwingBreaker = new Breaker();
    final GenericBreakerCtor<byte[], String, IllegalArgumentException> genericBreaker =
        new GenericBreakerCtor<>(BASE64_PARSE_HANDLER);
    final GenericBreakerParam<byte[], String, IllegalArgumentException> genericBreakerParam =
        new GenericBreakerParam<>();

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
  public void mixGenericBreaker(Mix mix, Blackhole bh) {
    byte[] bytes = mix.next();
    try {
      bh.consume(mix.genericBreaker.decode(bytes));
    } catch (IllegalArgumentException e) {
      bh.consume(e);
    }
  }

  @Benchmark
  public void mixGenericBreakerParam(Mix mix, Blackhole bh) {
    byte[] bytes = mix.next();
    try {
      bh.consume(mix.genericBreakerParam.decode(bytes, BASE64_PARSE_HANDLER));
    } catch (IllegalArgumentException e) {
      bh.consume(e);
    }
  }
}
