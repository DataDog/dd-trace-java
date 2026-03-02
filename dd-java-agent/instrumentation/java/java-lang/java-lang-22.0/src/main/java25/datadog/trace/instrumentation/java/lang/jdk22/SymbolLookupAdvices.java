package datadog.trace.instrumentation.java.lang.jdk22;

import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.bytebuddy.asm.Advice;

public class SymbolLookupAdvices {

  public static class DefaultLookup {
    public static class CaptureDefault {
      @Advice.OnMethodExit(suppress = Throwable.class)
      public static void onExit(@Advice.Return final SymbolLookup symbolLookup) {
        if (symbolLookup != null) {
          InstrumentationContext.get(SymbolLookup.class, String.class).put(symbolLookup, "default");
        }
      }
    }

    public static class CaptureLibraryName {
      @Advice.OnMethodExit(suppress = Throwable.class)
      public static void onExit(
          @Advice.Return final SymbolLookup symbolLookup,
          @Advice.Argument(0) final String libraryName) {
        if (symbolLookup != null && libraryName != null) {
          InstrumentationContext.get(SymbolLookup.class, String.class)
              .put(symbolLookup, libraryName.toLowerCase(Locale.ROOT));
        }
      }
    }
  }

  public static class CaptureLibraryPath {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Return final SymbolLookup symbolLookup,
        @Advice.Argument(0) final Path libraryPath) {
      if (symbolLookup != null && libraryPath != null) {
        InstrumentationContext.get(SymbolLookup.class, String.class)
            .put(symbolLookup, libraryPath.getFileName().toString().toLowerCase(Locale.ROOT));
      }
    }
  }

  public static class CaptureMemorySegment {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This final SymbolLookup self,
        @Advice.Argument(0) final String name,
        @Advice.Return final Optional<MemorySegment> maybeSegment) {
      if (name != null && maybeSegment != null && maybeSegment.isPresent()) {
        final String libName =
            InstrumentationContext.get(SymbolLookup.class, String.class).get(self);
        if (libName != null) {
          final Set<String> tracedMethods =
              InstrumenterConfig.get().getTraceNativeMethods().get(libName);
          if (tracedMethods != null && tracedMethods.contains(name)) {
            InstrumentationContext.get(MemorySegment.class, CharSequence.class)
                .put(maybeSegment.get(), UTF8BytesString.create(libName + "." + name));
          }
        }
      }
    }
  }
}
