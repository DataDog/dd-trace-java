package datadog.trace.agent.tooling.iast;

import static net.bytebuddy.utility.OpenedClassReader.ASM_API;

import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.iast.secrets.HardcodedSecretMatcher;
import java.util.Map;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

public class IastSecretVisitor extends ClassVisitor {

  private final Map<String, String> secrets;
  private final TriConsumer consumer;

  public IastSecretVisitor(final Map<String, String> secrets, final TriConsumer consumer) {
    super(ASM_API);
    this.secrets = secrets;
    this.consumer = consumer;
  }

  @Override
  public MethodVisitor visitMethod(
      int access, final String methodName, String desc, String signature, String[] exceptions) {
    return new IastMethodVisitor(secrets, methodName, consumer);
  }

  static class IastMethodVisitor extends MethodVisitor {

    private final Map<String, String> secrets;
    private final TriConsumer consumer;

    private final String method;

    private int currentLine;

    public IastMethodVisitor(
        final Map<String, String> secrets, final String method, final TriConsumer consumer) {
      super(ASM_API);
      this.secrets = secrets;
      this.consumer = consumer;
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
        if (literal.length() < HardcodedSecretMatcher.MIN_SECRET_LENGTH) {
          return;
        }
        String value = secrets.get(literal);
        if (value != null) {
          consumer.accept(method, value, currentLine);
        }
      }
    }
  }
}
