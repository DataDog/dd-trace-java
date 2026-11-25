package datadog.trace.agent.tooling.jit;

import java.util.Arrays;
import java.util.regex.Pattern;

/** Calls every rewritten method, around constructs that exercise the bytecode scanner. */
public class RewriteFixture {
  static final long BIG = 1234567890123L;
  static final double PI = 3.14159;

  public static String splitWhitespace(String s) {
    return Arrays.toString(s.split("\\s+"));
  }

  public static String splitPaddedComma(String s) {
    return Arrays.toString(s.split("\\s*,\\s*"));
  }

  public static String splitLimited(String s, int limit) {
    return Arrays.toString(s.split("::", limit));
  }

  public static String replaceLiteral(String s) {
    return s.replaceAll("\\.", "/");
  }

  public static String replaceRegex(String s) {
    return s.replaceAll("[aeiou]", "_");
  }

  public static String replaceFirstLiteral(String s) {
    return s.replaceFirst("::", "=");
  }

  public static boolean matches(String s) {
    return s.matches("[a-z]+\\d*");
  }

  public static boolean patternMatches(String s) {
    return Pattern.matches("x.*y", s);
  }

  /** A tableswitch, lookupswitch, long/double constants and a regex call in each branch. */
  public static String switches(String s, int k) {
    long l = BIG + k;
    double d = PI * k;
    switch (k) {
      case 0:
        return s.replaceAll("\\.", "!") + l;
      case 1:
        return s.split("\\s+").length + ":" + d;
      case 2:
        return String.valueOf(s.matches(".*b.*"));
      default:
        break;
    }
    switch (k * 1000) {
      case -7000:
        return "neg";
      case 99000:
        return s.replaceFirst("a", "A");
      default:
        return s;
    }
  }

  /** Enough locals that the compiler emits wide loads and stores. */
  public static String manyLocals(String s) {
    long a0 = 0, a1 = 1, a2 = 2, a3 = 3, a4 = 4, a5 = 5, a6 = 6, a7 = 7, a8 = 8, a9 = 9;
    long b0 = 0, b1 = 1, b2 = 2, b3 = 3, b4 = 4, b5 = 5, b6 = 6, b7 = 7, b8 = 8, b9 = 9;
    long c0 = 0, c1 = 1, c2 = 2, c3 = 3, c4 = 4, c5 = 5, c6 = 6, c7 = 7, c8 = 8, c9 = 9;
    long d0 = 0, d1 = 1, d2 = 2, d3 = 3, d4 = 4, d5 = 5, d6 = 6, d7 = 7, d8 = 8, d9 = 9;
    long e0 = 0, e1 = 1, e2 = 2, e3 = 3, e4 = 4, e5 = 5, e6 = 6, e7 = 7, e8 = 8, e9 = 9;
    long f0 = 0, f1 = 1, f2 = 2, f3 = 3, f4 = 4, f5 = 5, f6 = 6, f7 = 7, f8 = 8, f9 = 9;
    long g0 = 0, g1 = 1, g2 = 2, g3 = 3, g4 = 4, g5 = 5, g6 = 6, g7 = 7, g8 = 8, g9 = 9;
    long h0 = 0, h1 = 1, h2 = 2, h3 = 3, h4 = 4, h5 = 5, h6 = 6, h7 = 7, h8 = 8, h9 = 9;
    long i0 = 0, i1 = 1, i2 = 2, i3 = 3, i4 = 4, i5 = 5, i6 = 6, i7 = 7, i8 = 8, i9 = 9;
    long j0 = 0, j1 = 1, j2 = 2, j3 = 3, j4 = 4, j5 = 5, j6 = 6, j7 = 7, j8 = 8, j9 = 9;
    long k0 = 0, k1 = 1, k2 = 2, k3 = 3, k4 = 4, k5 = 5, k6 = 6, k7 = 7, k8 = 8, k9 = 9;
    long m0 = 0, m1 = 1, m2 = 2, m3 = 3, m4 = 4, m5 = 5, m6 = 6, m7 = 7, m8 = 8, m9 = 9;
    long n0 = 0, n1 = 1, n2 = 2, n3 = 3, n4 = 4, n5 = 5, n6 = 6, n7 = 7, n8 = 8, n9 = 9;
    String t = s;
    n9 += a0 + b1 + c2 + d3 + e4 + f5 + g6 + h7 + i8 + j9 + k0 + m1 + n2;
    for (int i = 0; i < 2; i++) {
      t = t.replaceAll("\\s+", "_");
      n8 += t.length();
    }
    return t + n9 + n8 + a9 + b8 + c7 + d6 + e5 + f4 + g3 + h2 + i1 + j0 + k9 + m8 + n7;
  }

  /** No rewritable calls; uses a split with the JDK's own fast path. */
  public static String fastPathOnly(String s) {
    return Arrays.toString(s.split(","));
  }
}
