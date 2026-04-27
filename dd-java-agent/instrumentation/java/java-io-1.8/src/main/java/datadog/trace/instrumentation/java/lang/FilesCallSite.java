package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.appsec.RaspCallSites;
import java.nio.file.Path;
import javax.annotation.Nullable;

@CallSite(
    spi = {RaspCallSites.class},
    helpers = FileIORaspHelper.class)
public class FilesCallSite {

  // ===================== WRITE =====================

  @CallSite.Before(
      "java.io.OutputStream java.nio.file.Files.newOutputStream(java.nio.file.Path, java.nio.file.OpenOption[])")
  @CallSite.Before(
      "java.nio.file.Path java.nio.file.Files.write(java.nio.file.Path, byte[], java.nio.file.OpenOption[])")
  @CallSite.Before(
      "java.nio.file.Path java.nio.file.Files.write(java.nio.file.Path, java.lang.Iterable, java.nio.charset.Charset, java.nio.file.OpenOption[])")
  @CallSite.Before(
      "java.nio.file.Path java.nio.file.Files.write(java.nio.file.Path, java.lang.Iterable, java.nio.file.OpenOption[])")
  // Java 11+: Files.writeString variants
  @CallSite.Before(
      "java.nio.file.Path java.nio.file.Files.writeString(java.nio.file.Path, java.lang.CharSequence, java.nio.file.OpenOption[])")
  @CallSite.Before(
      "java.nio.file.Path java.nio.file.Files.writeString(java.nio.file.Path, java.lang.CharSequence, java.nio.charset.Charset, java.nio.file.OpenOption[])")
  @CallSite.Before(
      "java.io.BufferedWriter java.nio.file.Files.newBufferedWriter(java.nio.file.Path, java.nio.charset.Charset, java.nio.file.OpenOption[])")
  @CallSite.Before(
      "java.io.BufferedWriter java.nio.file.Files.newBufferedWriter(java.nio.file.Path, java.nio.file.OpenOption[])")
  public static void beforeWrite(@CallSite.Argument(0) @Nullable final Path path) {
    if (path != null) {
      FileIORaspHelper.INSTANCE.beforeFileWritten(path.toString());
    }
  }

  @CallSite.Before(
      "long java.nio.file.Files.copy(java.io.InputStream, java.nio.file.Path, java.nio.file.CopyOption[])")
  public static void beforeCopyFromStream(@CallSite.Argument(1) @Nullable final Path target) {
    if (target != null) {
      FileIORaspHelper.INSTANCE.beforeFileWritten(target.toString());
    }
  }

  @CallSite.Before(
      "java.nio.file.Path java.nio.file.Files.copy(java.nio.file.Path, java.nio.file.Path, java.nio.file.CopyOption[])")
  public static void beforeCopyPathToPath(
      @CallSite.Argument(0) @Nullable final Path source,
      @CallSite.Argument(1) @Nullable final Path target) {
    if (source != null) {
      FileIORaspHelper.INSTANCE.beforeFileLoaded(source.toString());
    }
    if (target != null) {
      FileIORaspHelper.INSTANCE.beforeFileWritten(target.toString());
    }
  }

  @CallSite.Before(
      "java.nio.file.Path java.nio.file.Files.move(java.nio.file.Path, java.nio.file.Path, java.nio.file.CopyOption[])")
  public static void beforeMove(@CallSite.Argument(1) @Nullable final Path target) {
    if (target != null) {
      FileIORaspHelper.INSTANCE.beforeFileWritten(target.toString());
    }
  }

  @CallSite.Before("long java.nio.file.Files.copy(java.nio.file.Path, java.io.OutputStream)")
  public static void beforeCopyToStream(@CallSite.Argument(0) @Nullable final Path source) {
    if (source != null) {
      FileIORaspHelper.INSTANCE.beforeFileLoaded(source.toString());
    }
  }

  // ===================== READ =====================

  @CallSite.Before(
      "java.io.InputStream java.nio.file.Files.newInputStream(java.nio.file.Path, java.nio.file.OpenOption[])")
  @CallSite.Before("byte[] java.nio.file.Files.readAllBytes(java.nio.file.Path)")
  @CallSite.Before(
      "java.util.List java.nio.file.Files.readAllLines(java.nio.file.Path, java.nio.charset.Charset)")
  @CallSite.Before("java.util.List java.nio.file.Files.readAllLines(java.nio.file.Path)")
  // Java 11+: Files.readString variants
  @CallSite.Before("java.lang.String java.nio.file.Files.readString(java.nio.file.Path)")
  @CallSite.Before(
      "java.lang.String java.nio.file.Files.readString(java.nio.file.Path, java.nio.charset.Charset)")
  @CallSite.Before(
      "java.io.BufferedReader java.nio.file.Files.newBufferedReader(java.nio.file.Path, java.nio.charset.Charset)")
  @CallSite.Before(
      "java.io.BufferedReader java.nio.file.Files.newBufferedReader(java.nio.file.Path)")
  @CallSite.Before(
      "java.util.stream.Stream java.nio.file.Files.lines(java.nio.file.Path, java.nio.charset.Charset)")
  @CallSite.Before("java.util.stream.Stream java.nio.file.Files.lines(java.nio.file.Path)")
  public static void beforeRead(@CallSite.Argument(0) @Nullable final Path path) {
    if (path != null) {
      FileIORaspHelper.INSTANCE.beforeFileLoaded(path.toString());
    }
  }
}
