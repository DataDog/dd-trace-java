package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.appsec.RaspCallSites;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.Set;
import javax.annotation.Nullable;

@CallSite(
    spi = {RaspCallSites.class},
    helpers = FileIORaspHelper.class)
public class FileChannelCallSite {

  @CallSite.Before(
      "java.nio.channels.FileChannel java.nio.channels.FileChannel.open(java.nio.file.Path, java.nio.file.OpenOption[])")
  public static void beforeOpenArray(
      @CallSite.Argument(0) @Nullable final Path path,
      @CallSite.Argument(1) @Nullable final OpenOption[] options) {
    if (path != null) {
      String pathStr = path.toString();
      FileIORaspHelper.INSTANCE.beforeFileLoaded(pathStr);
      if (hasWriteOption(options)) {
        FileIORaspHelper.INSTANCE.beforeFileWritten(pathStr);
      }
    }
  }

  @CallSite.Before(
      "java.nio.channels.FileChannel java.nio.channels.FileChannel.open(java.nio.file.Path, java.util.Set, java.nio.file.attribute.FileAttribute[])")
  public static void beforeOpenSet(
      @CallSite.Argument(0) @Nullable final Path path,
      @CallSite.Argument(1) @Nullable final Set<? extends OpenOption> options,
      @CallSite.Argument(2) @Nullable final FileAttribute<?>[] attrs) {
    if (path != null) {
      String pathStr = path.toString();
      FileIORaspHelper.INSTANCE.beforeFileLoaded(pathStr);
      if (hasWriteOption(options)) {
        FileIORaspHelper.INSTANCE.beforeFileWritten(pathStr);
      }
    }
  }

  private static boolean hasWriteOption(@Nullable final OpenOption[] options) {
    if (options == null) {
      return false;
    }
    for (OpenOption opt : options) {
      if (isWriteOption(opt)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasWriteOption(@Nullable final Set<? extends OpenOption> options) {
    if (options == null) {
      return false;
    }
    for (OpenOption opt : options) {
      if (isWriteOption(opt)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isWriteOption(final OpenOption opt) {
    return opt == StandardOpenOption.WRITE
        || opt == StandardOpenOption.APPEND
        || opt == StandardOpenOption.CREATE
        || opt == StandardOpenOption.CREATE_NEW
        || opt == StandardOpenOption.TRUNCATE_EXISTING;
  }
}
