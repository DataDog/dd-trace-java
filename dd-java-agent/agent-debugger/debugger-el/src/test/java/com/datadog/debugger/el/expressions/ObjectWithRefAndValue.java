package com.datadog.debugger.el.expressions;

class ObjectWithRefAndValue {
  private ObjectWithRefAndValue ref;
  private String b;

  public ObjectWithRefAndValue(ObjectWithRefAndValue ref, String b) {
    this.ref = ref;
    this.b = b;
  }

  public ObjectWithRefAndValue getRef() {
    return ref;
  }

  public String getB() {
    return b;
  }
}

class ExObjectWithRefAndValue extends ObjectWithRefAndValue {
  public ExObjectWithRefAndValue(ObjectWithRefAndValue ref, String b) {
    super(ref, b);
  }
}
