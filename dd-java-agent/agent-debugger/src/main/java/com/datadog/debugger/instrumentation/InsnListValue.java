package com.datadog.debugger.instrumentation;

import com.datadog.debugger.el.Value;
import org.objectweb.asm.tree.InsnList;

/** Wraps instruction list as a value for expression language */
public class InsnListValue implements Value<InsnList> {
  private final InsnList node;

  public InsnListValue(InsnList node) {
    this.node = node;
  }

  @Override
  public InsnList getValue() {
    return node;
  }

  @Override
  public boolean isUndefined() {
    return false;
  }

  @Override
  public boolean isNull() {
    return node == null;
  }
}
