package datadog.trace.util;

import java.util.function.Supplier;

public final class JDK9ExecutableSupplier implements Supplier<String> {
  @Override
  public String get() {
    return ProcessHandle.current()
        .info()
        .command()
        .orElseThrow(() -> new UnsupportedOperationException("Executable path is not available"));
  }
}
