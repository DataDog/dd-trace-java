package com.datadog.iast.util;

import com.datadog.iast.Reporter;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class IastClassVisitor extends ClassVisitor {

  /** Constant string to find. */
  private final String needle;

  private final String clazz;
  private final String secretEvidence;
  private final Reporter reporter;

  public IastClassVisitor(
      final String needle,
      final String clazz,
      final String secretEvidence,
      final Reporter reporter) {
    super(Opcodes.ASM9);
    this.needle = needle;
    this.clazz = clazz;
    this.secretEvidence = secretEvidence;
    this.reporter = reporter;
  }

  @Override
  public MethodVisitor visitMethod(
      int access, final String methodName, String desc, String signature, String[] exceptions) {
    return new IastMethodVisitor(needle, clazz, methodName, secretEvidence, reporter);
  }

  static class IastMethodVisitor extends MethodVisitor {

    private final String needle;
    private final String clazz;
    private final String secretEvidence;
    private final Reporter reporter;

    private final String method;

    private int currentLine;

    public IastMethodVisitor(
        final String needle,
        final String clazz,
        final String method,
        final String secretEvidence,
        final Reporter reporter) {
      super(Opcodes.ASM9);
      this.needle = needle;
      this.clazz = clazz;
      this.secretEvidence = secretEvidence;
      this.reporter = reporter;
      this.method = method;
    }

    @Override
    public void visitLineNumber(int line, Label start) {
      currentLine = line;
    }

    @Override
    public void visitLdcInsn(Object cst) {
      if (cst instanceof String && ((String) cst).equals(needle)) {
        reporter.report(
            null,
            new Vulnerability(
                VulnerabilityType.HARDCODED_SECRET,
                Location.forClassAndMethodAndLine(clazz, method, currentLine),
                new Evidence(secretEvidence)));
      }
    }
  }
}
