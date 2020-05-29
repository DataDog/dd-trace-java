package com.datadog.profiling.mlt;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;

@EqualsAndHashCode
class FrameElement {
  @EqualsAndHashCode.Exclude
  private final ConstantPool<String> stringPool;

  private final int ownerPtr;
  private final int methodPtr;

  @Getter
  private final int line;

  FrameElement(int ownerPtr, int methodPtr, int line, @NonNull ConstantPool<String> stringPool) {
    this.stringPool = stringPool;
    this.ownerPtr = ownerPtr;
    this.methodPtr = methodPtr;
    this.line = line;
  }

  FrameElement(@NonNull String owner, @NonNull String method, int line, @NonNull ConstantPool<String> stringPool) {
    this.stringPool = stringPool;
    this.ownerPtr = stringPool.get(owner);
    this.methodPtr = stringPool.get(method);
    this.line = line;
  }

  String getOwner() {
    return stringPool.get(ownerPtr);
  }

  String getMethod() {
    return stringPool.get(methodPtr);
  }

  int getOwnerPtr() {
    return ownerPtr;
  }

  int getMethodPtr() {
    return methodPtr;
  }

  @Override
  public String toString() {
    return getOwner() + "." + getMethod() + "(" + line + ")";
  }
}
