package com.datadog.mlt.io;

import lombok.EqualsAndHashCode;
import lombok.Generated;
import lombok.Getter;
import lombok.NonNull;

/** A single stack frame element representation */
@EqualsAndHashCode
public final class FrameElement {
  @EqualsAndHashCode.Exclude private final ConstantPool<String> stringPool;

  private final int ownerPtr;
  private final int methodPtr;

  @Getter private final int line;

  FrameElement(int ownerPtr, int methodPtr, int line, @NonNull ConstantPool<String> stringPool) {
    this.stringPool = stringPool;
    this.ownerPtr = ownerPtr;
    this.methodPtr = methodPtr;
    this.line = line;
  }

  public FrameElement(
      @NonNull String owner,
      @NonNull String method,
      int line,
      @NonNull ConstantPool<String> stringPool) {
    this.stringPool = stringPool;
    this.ownerPtr = stringPool.getOrInsert(owner);
    this.methodPtr = stringPool.getOrInsert(method);
    this.line = line;
  }

  /**
   * Owner type
   *
   * @return the owner type (aka class) string
   */
  public String getOwner() {
    return stringPool.get(ownerPtr);
  }

  /**
   * Frame method with full signature
   *
   * @return method name string with full signature
   */
  public String getMethod() {
    return stringPool.get(methodPtr);
  }

  /**
   * Owner type name constant pool index
   *
   * @return the owner type name constant pool index
   */
  int getOwnerPtr() {
    return ownerPtr;
  }

  /**
   * Method name string constant pool index
   *
   * @return the method name string constant pool index
   */
  int getMethodPtr() {
    return methodPtr;
  }

  @Generated
  @Override
  public String toString() {
    return getOwner() + "." + getMethod() + "(" + line + ")";
  }
}
