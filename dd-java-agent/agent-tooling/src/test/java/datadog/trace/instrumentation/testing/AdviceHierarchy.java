package datadog.trace.instrumentation.testing;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassWriter;

public final class AdviceHierarchy {
  private AdviceHierarchy() {}

  public static class Superclass {
    public static int muzzleCheck(ClassReader reader) {
      return reader.getAccess();
    }
  }

  public interface Interface {
    default int muzzleCheck(ClassWriter writer) {
      return writer.newUTF8("muzzle");
    }
  }
}
