package datadog.telemetry.dependency;

import java.util.function.Consumer;
import java.util.jar.JarFile;
import net.bytebuddy.asm.Advice;

public class CloseCallbackAdvice {
  public static Consumer<JarFile> BEFORE;
  public static Consumer<JarFile> AFTER;

  @Advice.OnMethodEnter
  static void enter(@Advice.This final JarFile jarFile) {
    if (BEFORE != null) {
      BEFORE.accept(jarFile);
    }
  }

  @Advice.OnMethodExit
  static void exit(@Advice.This final JarFile jarFile) {
    if (AFTER != null) {
      AFTER.accept(jarFile);
    }
  }
}
