package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.appsec.RaspCallSites;
import java.io.File;
import javax.annotation.Nullable;

@CallSite(
    spi = {RaspCallSites.class},
    helpers = FileIORaspHelper.class)
public class FileReaderCallSite {

  @CallSite.Before("void java.io.FileReader.<init>(java.lang.String)")
  // Java 11+: FileReader(String, Charset)
  @CallSite.Before("void java.io.FileReader.<init>(java.lang.String, java.nio.charset.Charset)")
  public static void beforeConstructor(@CallSite.Argument(0) @Nullable final String path) {
    if (path != null) {
      raspCallback(path);
    }
  }

  @CallSite.Before("void java.io.FileReader.<init>(java.io.File)")
  // Java 11+: FileReader(File, Charset)
  @CallSite.Before("void java.io.FileReader.<init>(java.io.File, java.nio.charset.Charset)")
  public static void beforeConstructorFile(@CallSite.Argument(0) @Nullable final File file) {
    if (file != null) {
      raspCallback(file.getPath());
    }
  }

  private static void raspCallback(final String path) {
    FileIORaspHelper.INSTANCE.beforeFileLoaded(path);
  }
}
