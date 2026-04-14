package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.appsec.RaspCallSites;
import java.nio.file.Path;
import javax.annotation.Nullable;

@CallSite(
    spi = {RaspCallSites.class},
    helpers = FileIORaspHelper.class)
public class FileChannelCallSite {

  @CallSite.Before(
      "java.nio.channels.FileChannel java.nio.channels.FileChannel.open(java.nio.file.Path, java.nio.file.OpenOption[])")
  @CallSite.Before(
      "java.nio.channels.FileChannel java.nio.channels.FileChannel.open(java.nio.file.Path, java.util.Set, java.nio.file.attribute.FileAttribute[])")
  public static void beforeOpen(@CallSite.Argument(0) @Nullable final Path path) {
    if (path != null) {
      // Fire both read and write callbacks: the WAF determines whether to block
      // based on the full request context (e.g. zipslip requires body.filenames too).
      FileIORaspHelper.INSTANCE.beforeFileLoaded(path.toString());
      FileIORaspHelper.INSTANCE.beforeFileWritten(path.toString());
    }
  }
}
