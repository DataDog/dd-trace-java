package com.datadog.iast.util;

import com.datadog.iast.Reporter;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.instrumentation.iastinstrumenter.IastHardcodedSecretListener;
import java.util.Map;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class IastSecretVisitor extends ClassVisitor {

  private final Map<String, String> secrets;
  private final String clazz;
  private final Reporter reporter;

  public IastSecretVisitor(
      final Map<String, String> secrets, final String clazz, final Reporter reporter) {
    super(Opcodes.ASM9);
    this.secrets = secrets;
    this.clazz = clazz;
    this.reporter = reporter;
  }

  @Override
  public MethodVisitor visitMethod(
      int access, final String methodName, String desc, String signature, String[] exceptions) {
    return new IastMethodVisitor(secrets, clazz, methodName, reporter);
  }

  static class IastMethodVisitor extends MethodVisitor {

    private final Map<String, String> secrets;
    private final String clazz;
    private final Reporter reporter;

    private final String method;

    private int currentLine;

    public IastMethodVisitor(
        final Map<String, String> secrets,
        final String clazz,
        final String method,
        final Reporter reporter) {
      super(Opcodes.ASM9);
      this.secrets = secrets;
      this.clazz = clazz;
      this.reporter = reporter;
      this.method = method;
    }

    @Override
    public void visitLineNumber(int line, Label start) {
      currentLine = line;
    }

    @Override
    public void visitLdcInsn(Object cst) {
      if (cst instanceof String) {
        String literal = ((String) cst);
        if (literal.length() < IastHardcodedSecretListener.MIN_SECRET_LENGTH) {
          return;
        }
        secrets.forEach(
            (key, value) -> {
              if (key.equals(literal)) {
                reporter.report(
                    null,
                    new Vulnerability(
                        VulnerabilityType.HARDCODED_SECRET,
                        Location.forClassAndMethodAndLine(clazz, method, currentLine),
                        new Evidence(value)));
              }
            });
      }
    }
  }
}
