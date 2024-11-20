package com.datadog.iast.securitycontrol;

import datadog.trace.api.iast.securitycontrol.SecurityControl;
import org.objectweb.asm.MethodVisitor;

public class SecurityControlMethodAdapter extends MethodVisitor {


  protected SecurityControlMethodAdapter(int api) {
    super(api);
  }
}
