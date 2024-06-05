package datadog.opentelemetry.tooling;

import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.commons.MethodRemapper;
import net.bytebuddy.jar.asm.commons.Remapper;

/** Maps OpenTelemetry method calls to use the Datadog equivalent API. */
public final class OtelMethodCallMapper extends MethodRemapper {
  public OtelMethodCallMapper(MethodVisitor methodVisitor, Remapper remapper) {
    super(methodVisitor, remapper);
  }
}
