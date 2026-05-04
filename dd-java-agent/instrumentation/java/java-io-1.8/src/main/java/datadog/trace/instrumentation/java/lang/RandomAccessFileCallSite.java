package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.appsec.RaspCallSites;
import java.io.File;
import javax.annotation.Nullable;

@CallSite(
    spi = {RaspCallSites.class},
    helpers = FileIORaspHelper.class)
public class RandomAccessFileCallSite {

  @CallSite.Before("void java.io.RandomAccessFile.<init>(java.lang.String, java.lang.String)")
  public static void beforeConstructor(
      @CallSite.Argument(0) @Nullable final String name,
      @CallSite.Argument(1) @Nullable final String mode) {
    if (name != null && mode != null) {
      FileIORaspHelper.INSTANCE.beforeRandomAccessFileOpened(name, mode);
    }
  }

  @CallSite.Before("void java.io.RandomAccessFile.<init>(java.io.File, java.lang.String)")
  public static void beforeConstructorFile(
      @CallSite.Argument(0) @Nullable final File file,
      @CallSite.Argument(1) @Nullable final String mode) {
    if (file != null && mode != null) {
      FileIORaspHelper.INSTANCE.beforeRandomAccessFileOpened(file.getPath(), mode);
    }
  }
}
