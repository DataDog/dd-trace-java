package datadog.trace.util

import spock.lang.Specification

class UnsafeUtilsTest extends Specification {

  def "test try shallow clone does not clone final fields"() {
    given:
    def inner = new MyClass("a", false, [], "b", 2, null, null)
    def instance = new MyClass("aaa", true, [ 4, 5, 6, ], "ddd", 1, new int[] {
      1, 2, 3
    }, inner)

    when:
    def clone = UnsafeUtils.tryShallowClone(instance)

    then:
    clone !== instance
    clone.a == null
    clone.b == false
    clone.c == null
    clone.d == null
    clone.e == 0
    clone.f == null
    clone.g == null
  }

  def "test try shallow clone clones non-final fields"() {
    given:
    def instance = new MyNonFinalClass("a", 1, [2, 3, 4])

    when:
    def clone = UnsafeUtils.tryShallowClone(instance)

    then:
    clone !== instance
    clone.h == instance.h
    clone.j == instance.j
    clone.k === instance.k
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

  private static class MyNonFinalClass {
    private String h
    private int j
    private List<Integer> k

    MyNonFinalClass(String h, int j, List<Integer> k) {
      this.h = h
      this.j = j
      this.k = k
    }
  }
}
