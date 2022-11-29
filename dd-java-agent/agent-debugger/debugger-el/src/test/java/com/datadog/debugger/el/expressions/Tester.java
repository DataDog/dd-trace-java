package com.datadog.debugger.el.expressions;

class Tester {
  private Tester ref;
  private String b;

  public Tester(Tester ref, String b) {
    this.ref = ref;
    this.b = b;
  }

  public Tester getRef() {
    return ref;
  }

  public String getB() {
    return b;
  }
}

class ExTester extends Tester {
  public ExTester(Tester ref, String b) {
    super(ref, b);
  }
}
