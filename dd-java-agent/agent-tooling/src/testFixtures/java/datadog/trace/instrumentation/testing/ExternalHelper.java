package datadog.trace.instrumentation.testing;

import net.bytebuddy.jar.asm.Type;

public final class ExternalHelper {
  private ExternalHelper() {}

  public static String typeName() {
    return Type.getType(Object.class).getClassName();
  }
}
