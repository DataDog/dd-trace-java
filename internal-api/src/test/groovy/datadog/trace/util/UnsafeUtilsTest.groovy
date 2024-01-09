package datadog.trace.util

import spock.lang.Specification

class UnsafeUtilsTest extends Specification {

  def "test try shallow clone"() {
    given:
    def inner = new MyClass("a", false, [], "b", 2, null, null)
    def instance = new MyClass("aaa", true, [ 4, 5, 6, ], "ddd", 1, new int[] {
      1, 2, 3
    }, inner)

    when:
    def clone = UnsafeUtils.tryShallowClone(instance)

    then:
    clone !== instance
    clone.a === instance.a
    clone.b == instance.b
    clone.c === instance.c
    clone.d === instance.d
    clone.e == instance.e
    clone.f === instance.f
    clone.g === instance.g
  }

  private static class MyParentClass {
    public static final String CONSTANT = "constant"

    private final String a
    private final boolean b
    private final List<Integer> c

    protected MyParentClass(String a, boolean b, List<Integer> c) {
      this.a = a
      this.b = b
      this.c = c
    }

    String getA() {
      return a
    }

    boolean getB() {
      return b
    }

    List<Integer> getC() {
      return c
    }
  }

  private static final class MyClass extends MyParentClass {
    private final String d
    private final int e
    private final int[] f
    private final MyClass g

    private MyClass(String a, boolean b, List<Integer> c, String d, int e, int[] f, MyClass g) {
      super(a, b, c)
      this.d = d
      this.e = e
      this.f = f
      this.g = g
    }
  }
}
