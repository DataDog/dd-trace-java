package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnsafeUtilsTest {

  @Test
  void tryShallowClone() {
    MyClass inner = new MyClass("a", false, Arrays.asList(), "b", 2, null, null);
    MyClass instance =
        new MyClass("aaa", true, Arrays.asList(4, 5, 6), "ddd", 1, new int[] {1, 2, 3}, inner);

    MyClass clone = UnsafeUtils.tryShallowClone(instance);

    assertNotSame(instance, clone);
    assertSame(instance.getA(), clone.getA());
    assertEquals(instance.getB(), clone.getB());
    assertSame(instance.getC(), clone.getC());
    assertSame(instance.d, clone.d);
    assertEquals(instance.e, clone.e);
    assertSame(instance.f, clone.f);
    assertSame(instance.g, clone.g);
  }

  private static class MyParentClass {
    public static final String CONSTANT = "constant";

    private final String a;
    private final boolean b;
    private final List<Integer> c;

    protected MyParentClass(String a, boolean b, List<Integer> c) {
      this.a = a;
      this.b = b;
      this.c = c;
    }

    String getA() {
      return a;
    }

    boolean getB() {
      return b;
    }

    List<Integer> getC() {
      return c;
    }
  }

  private static final class MyClass extends MyParentClass {
    private final String d;
    private final int e;
    private final int[] f;
    private final MyClass g;

    private MyClass(String a, boolean b, List<Integer> c, String d, int e, int[] f, MyClass g) {
      super(a, b, c);
      this.d = d;
      this.e = e;
      this.f = f;
      this.g = g;
    }
  }
}
