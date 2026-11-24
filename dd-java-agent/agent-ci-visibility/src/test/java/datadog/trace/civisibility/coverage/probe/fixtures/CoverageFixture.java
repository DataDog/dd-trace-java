package datadog.trace.civisibility.coverage.probe.fixtures;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Code shapes for the line coverage tests. Every scenario runs a different set of lines. */
@SuppressWarnings({"unused", "ConstantConditions", "divzero", "finally", "UnusedAssignment"})
public final class CoverageFixture {

  static final List<String> STATIC_LIST = new ArrayList<>();
  static int staticCounter;

  static {
    STATIC_LIST.add("a");
    if (STATIC_LIST.size() > 5) {
      STATIC_LIST.add("never");
    }
    staticCounter = 1;
  }

  public static final int SCENARIOS = 48;

  public static Object run(int scenario) throws Exception {
    switch (scenario) {
      case 0:
        return branches(1);
      case 1:
        return branches(-1);
      case 2:
        return branches(0);
      case 3:
        return loops(0);
      case 4:
        return loops(7);
      case 5:
        return tableSwitch(2);
      case 6:
        return tableSwitch(9);
      case 7:
        return lookupSwitch(1000);
      case 8:
        return stringSwitch("beta");
      case 9:
        return stringSwitch("none");
      case 10:
        return tryCatch(0);
      case 11:
        return tryCatch(1);
      case 12:
        return tryCatch(2);
      case 13:
        return tryFinally(false);
      case 14:
        return tryFinally(true);
      case 15:
        return nestedTry(0);
      case 16:
        return nestedTry(1);
      case 17:
        return nestedTry(2);
      case 18:
        return throwsMidLine(0);
      case 19:
        return throwsMidLine(3);
      case 20:
        return divide(1);
      case 21:
        return divide(0);
      case 22:
        return arrays(1);
      case 23:
        return arrays(5);
      case 24:
        return nullDereference(false);
      case 25:
        return nullDereference(true);
      case 26:
        return new Child(true).value;
      case 27:
        return new Child(false).value;
      case 28:
        return new Child("x", 3).value;
      case 29:
        return lambdas(3);
      case 30:
        return streams(Arrays.asList(1, 2, 3, 4));
      case 31:
        return anonymous(2);
      case 32:
        return synchronizedBlock(4);
      case 33:
        return labeled(3);
      case 34:
        return doWhile(0);
      case 35:
        return ternary(true) + ternary(false);
      case 36:
        return multiCatch(0);
      case 37:
        return multiCatch(1);
      case 38:
        return tryWithResources(false);
      case 39:
        return tryWithResources(true);
      case 40:
        return recursion(5);
      case 41:
        return Color.valueOf("GREEN").code();
      case 42:
        return longsAndDoubles(3);
      case 43:
        return earlyReturns(-5);
      case 44:
        return earlyReturns(5);
      case 45:
        return earlyReturns(50);
      case 46:
        return catchAndRethrow();
      case 47:
        return Holder.LAZY + STATIC_LIST.size() + staticCounter;
      default:
        throw new IllegalArgumentException("no scenario " + scenario);
    }
  }

  static String branches(int x) {
    String result;
    if (x > 0) {
      result = "positive";
    } else if (x < 0) {
      result = "negative";
    } else {
      result = "zero";
    }
    return result;
  }

  static int loops(int n) {
    int sum = 0;
    for (int i = 0; i < n; i++) {
      if (i == 2) {
        continue;
      }
      if (i == 5) {
        break;
      }
      sum += i;
    }
    int j = n;
    while (j > 0) {
      j -= 3;
    }
    return sum + j;
  }

  static String tableSwitch(int x) {
    switch (x) {
      case 1:
        return "one";
      case 2:
        return "two";
      case 3:
      case 4:
        return "three or four";
      default:
        return "other";
    }
  }

  static int lookupSwitch(int x) {
    int r;
    switch (x) {
      case 10:
        r = 1;
        break;
      case 1000:
        r = 2;
      // fall through
      case 100000:
        r = 3;
        break;
      default:
        r = 4;
    }
    return r;
  }

  static int stringSwitch(String s) {
    switch (s) {
      case "alpha":
        return 1;
      case "beta":
        return 2;
      default:
        return 0;
    }
  }

  static int tryCatch(int mode) {
    int r = 0;
    try {
      r += 1;
      fail(mode == 1, "one");
      r += 2;
      fail(mode == 2, "two");
      r += 4;
    } catch (IllegalStateException e) {
      r += 100;
    }
    return r;
  }

  static int tryFinally(boolean throwing) {
    int r = 0;
    try {
      try {
        r = 1;
        fail(throwing, "finally");
        r = 2;
      } finally {
        r += 10;
      }
    } catch (IllegalStateException e) {
      r += 1000;
    }
    return r;
  }

  static int nestedTry(int mode) {
    int r = 0;
    try {
      r++;
      try {
        r++;
        if (mode == 1) {
          throw new UnsupportedOperationException("inner");
        }
        r++;
        if (mode == 2) {
          throw new IllegalArgumentException("outer");
        }
      } catch (UnsupportedOperationException e) {
        r += 10;
      }
      r++;
    } catch (IllegalArgumentException e) {
      r += 100;
    }
    return r;
  }

  static int throwsMidLine(int limit) {
    int[] values = new int[2];
    int r = 0;
    try {
      r = values[0] + values[1] + values[limit] + compute(r);
      r++;
    } catch (ArrayIndexOutOfBoundsException e) {
      r = -1;
    }
    return r;
  }

  static int divide(int d) {
    try {
      int q = 10 / d;
      return q;
    } catch (ArithmeticException e) {
      return -1;
    }
  }

  static int arrays(int index) {
    int[] a = {1, 2, 3};
    try {
      a[index] = 7;
      return a[index];
    } catch (ArrayIndexOutOfBoundsException e) {
      return -2;
    }
  }

  static int nullDereference(boolean isNull) {
    String s = isNull ? null : "text";
    try {
      int length = s.length();
      return length;
    } catch (NullPointerException e) {
      return -3;
    }
  }

  static Object lambdas(int n) throws Exception {
    Function<Integer, Integer> square = x -> x * x;
    Function<Integer, Integer> multiLine =
        x -> {
          int y = x + 1;
          return y * 2;
        };
    Callable<String> callable = () -> "called";
    return square.apply(n) + multiLine.apply(n) + callable.call();
  }

  static Object streams(List<Integer> values) {
    return values.stream().filter(v -> v % 2 == 0).map(v -> v * 10).collect(Collectors.toList());
  }

  static int anonymous(int n) {
    Function<Integer, Integer> f =
        new Function<Integer, Integer>() {
          @Override
          public Integer apply(Integer x) {
            return x + 40;
          }
        };
    return f.apply(n);
  }

  static int synchronizedBlock(int n) {
    Object lock = new Object();
    int r;
    synchronized (lock) {
      r = n * 2;
    }
    return r;
  }

  static int labeled(int n) {
    int count = 0;
    outer:
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        if (j > i) {
          continue outer;
        }
        if (i == 2 && j == 1) {
          break outer;
        }
        count++;
      }
    }
    return count;
  }

  static int doWhile(int n) {
    int i = 0;
    do {
      i++;
    } while (i < n);
    return i;
  }

  static int ternary(boolean flag) {
    return flag ? 1 : 2;
  }

  static String multiCatch(int mode) {
    try {
      if (mode == 0) {
        throw new IllegalStateException();
      }
      throw new UnsupportedOperationException();
    } catch (IllegalStateException | UnsupportedOperationException e) {
      return e.getClass().getSimpleName();
    }
  }

  static int tryWithResources(boolean throwing) {
    int[] closed = new int[1];
    try (Closeable resource = () -> closed[0]++) {
      fail(throwing, "resource");
      return closed[0];
    } catch (Exception e) {
      return 10 + closed[0];
    }
  }

  static int recursion(int n) {
    if (n <= 1) {
      return 1;
    }
    return n * recursion(n - 1);
  }

  static double longsAndDoubles(int n) {
    long l = n * 3L;
    double d = l / 2.0;
    if (d > 4) {
      return d + l;
    }
    return d - l;
  }

  static int earlyReturns(int x) {
    if (x < 0) return -1;
    if (x < 10) return 1;
    int y = x * 2;
    return y;
  }

  static int catchAndRethrow() {
    try {
      try {
        fail(true, "rethrown");
        return 0;
      } catch (IllegalStateException e) {
        throw new RuntimeException(e);
      }
    } catch (RuntimeException e) {
      return 5;
    }
  }

  static int compute(int x) {
    return x + 1;
  }

  static void fail(boolean condition, String message) {
    if (condition) {
      throw new IllegalStateException(message);
    }
  }

  static class Base {
    final int value;

    Base(int value) {
      this.value = value;
    }
  }

  static final class Child extends Base {
    Child(boolean flag) {
      super(flag ? 10 : 20);
    }

    Child(String name, int count) {
      this(name.length() + count > 2);
    }
  }

  enum Color {
    RED(1),
    GREEN(2);

    private final int code;

    Color(int code) {
      this.code = code;
    }

    int code() {
      return code;
    }
  }

  static final class Holder {
    static final String LAZY = compute(41) + "!";
  }
}
