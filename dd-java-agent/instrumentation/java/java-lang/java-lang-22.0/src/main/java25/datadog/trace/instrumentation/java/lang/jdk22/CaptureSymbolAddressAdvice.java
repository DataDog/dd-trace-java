package datadog.trace.instrumentation.java.lang.jdk22;

import static datadog.trace.bootstrap.instrumentation.ffm.NativeLibraryHelper.onSymbolLookup;

import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;

public class CaptureSymbolAddressAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(
      @Advice.Argument(0) final String symbol,
      @Advice.This final Object self,
      @Advice.Return final long address) {
    final String libraryName =
        (String)
            InstrumentationContext.get("jdk.internal.loader.NativeLibrary", "java.lang.String")
                .get(self);
    onSymbolLookup(libraryName, symbol, address);
  }
}
