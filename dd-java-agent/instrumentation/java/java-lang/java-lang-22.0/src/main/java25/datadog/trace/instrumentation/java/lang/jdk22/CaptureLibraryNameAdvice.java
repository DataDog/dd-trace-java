package datadog.trace.instrumentation.java.lang.jdk22;

import datadog.trace.bootstrap.InstrumentationContext;
import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;
import net.bytebuddy.asm.Advice;

public class CaptureLibraryNameAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(@Advice.This final Object self) {
    // this module is not opened by default hence we are inlining this code into the target to
    // circumvent this limitation
    try {
      final MethodHandle mh =
          MethodHandles.lookup()
              .findVirtual(self.getClass(), "name", MethodType.methodType(String.class));
      String libraryName = (String) mh.invoke(self);
      if (libraryName != null) {
        libraryName = new File(libraryName).getName().toLowerCase(Locale.ROOT);
        int dot = libraryName.lastIndexOf('.');
        libraryName = (dot > 0) ? libraryName.substring(0, dot) : libraryName;
      } else {
        libraryName = "";
      }
      InstrumentationContext.get("jdk.internal.loader.NativeLibrary", "java.lang.String")
          .put(self, libraryName);
    } catch (Throwable ignored) {
    }
  }
}
