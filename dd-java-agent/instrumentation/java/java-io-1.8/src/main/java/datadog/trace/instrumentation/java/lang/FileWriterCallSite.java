package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.appsec.RaspCallSites;
import java.io.File;
import javax.annotation.Nullable;

@CallSite(
    spi = {RaspCallSites.class},
    helpers = FileIORaspHelper.class)
public class FileWriterCallSite {

  @CallSite.Before("void java.io.FileWriter.<init>(java.lang.String)")
  @CallSite.Before("void java.io.FileWriter.<init>(java.lang.String, boolean)")
  // Java 11+: FileWriter(String, Charset) and FileWriter(String, Charset, boolean)
  @CallSite.Before("void java.io.FileWriter.<init>(java.lang.String, java.nio.charset.Charset)")
  @CallSite.Before(
      "void java.io.FileWriter.<init>(java.lang.String, java.nio.charset.Charset, boolean)")
  public static void beforeConstructor(@CallSite.Argument(0) @Nullable final String path) {
    if (path != null) {
      raspCallback(path);
    }
  }

  @CallSite.Before("void java.io.FileWriter.<init>(java.io.File)")
  @CallSite.Before("void java.io.FileWriter.<init>(java.io.File, boolean)")
  // Java 11+: FileWriter(File, Charset) and FileWriter(File, Charset, boolean)
  @CallSite.Before("void java.io.FileWriter.<init>(java.io.File, java.nio.charset.Charset)")
  @CallSite.Before(
      "void java.io.FileWriter.<init>(java.io.File, java.nio.charset.Charset, boolean)")
  public static void beforeConstructorFile(@CallSite.Argument(0) @Nullable final File file) {
    if (file != null) {
      raspCallback(file.getPath());
    }
  }

  private static void raspCallback(final String path) {
    FileIORaspHelper.INSTANCE.beforeFileWritten(path);
  }
}
