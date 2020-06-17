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

  @EqualsAndHashCode.Exclude private final int cpIndex;

  FrameElement(
      int ptr,
      int ownerPtr,
      int methodPtr,
      int line,
      @NonNull ConstantPool<String> stringPool,
      @NonNull ConstantPool<FrameElement> framePool) {
    this.stringPool = stringPool;
    this.ownerPtr = ownerPtr;
    this.methodPtr = methodPtr;
    this.line = line;
    this.cpIndex = ptr != -1 ? ptr : framePool.getOrInsert(this); // escaping `this` but probably ok - class is final
  }

  public FrameElement(
      @NonNull String owner,
      @NonNull String method,
      int line,
      @NonNull ConstantPool<String> stringPool,
      @NonNull ConstantPool<FrameElement> framePool) {
    this.stringPool = stringPool;
    this.ownerPtr = stringPool.getOrInsert(owner);
    this.methodPtr = stringPool.getOrInsert(method);
    this.line = line;
    this.cpIndex = framePool.getOrInsert(this); // escaping `this` but probably ok - class is final
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

  int getCpIndex() {
    return cpIndex;
  }

  @Generated
  @Override
  public String toString() {
    return getOwner() + "." + getMethod() + "(" + line + ")";
  }
}
