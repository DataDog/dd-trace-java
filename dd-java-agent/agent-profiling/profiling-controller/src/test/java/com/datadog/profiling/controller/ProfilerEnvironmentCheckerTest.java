package com.datadog.profiling.controller;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadoghq.profiler.LibraryLoader;
import datadog.environment.OperatingSystem;
import datadog.libs.ddprof.DdprofLibraryLoader;
import datadog.trace.util.TempLocationManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class ProfilerEnvironmentCheckerTest {
  @TempDir Path temp;

  private final TempLocationManager tempManager = mock(TempLocationManager.class);
  private MockedStatic<OperatingSystem> os;
  private MockedStatic<TempLocationManager> tempLocations;
  private MockedStatic<LibraryLoader> libraries;
  private MockedStatic<DdprofLibraryLoader> scratchLocations;
  private LibraryLoader.Builder builder;

  @BeforeEach
  void setUp() throws Exception {
    os = mockStatic(OperatingSystem.class, CALLS_REAL_METHODS);
    os.when(OperatingSystem::isLinux).thenReturn(true);
    tempLocations = mockStatic(TempLocationManager.class);
    tempLocations.when(TempLocationManager::getInstance).thenReturn(tempManager);
    when(tempManager.getTempDir()).thenReturn(temp);

    scratchLocations = mockStatic(DdprofLibraryLoader.class, CALLS_REAL_METHODS);
    scratchLocations
        .when(DdprofLibraryLoader::javaProfiler)
        .thenReturn(mock(DdprofLibraryLoader.JavaProfilerHolder.class));
    scratchLocations
        .when(DdprofLibraryLoader::jvmAccess)
        .thenReturn(mock(DdprofLibraryLoader.JVMAccessHolder.class));
    scratchLocations
        .when(DdprofLibraryLoader::otelContext)
        .thenReturn(mock(DdprofLibraryLoader.OTelContextHolder.class));

    builder = mock(LibraryLoader.Builder.class, RETURNS_SELF);
    when(builder.load()).thenReturn(LibraryLoader.Result.SUCCESS);
    libraries = mockStatic(LibraryLoader.class);
    libraries.when(LibraryLoader::builder).thenReturn(builder);
  }

  @AfterEach
  void tearDown() {
    libraries.close();
    scratchLocations.close();
    tempLocations.close();
    os.close();
  }

  @Test
  void checksProfilerTempDirAndPreservesItsFiles() throws Exception {
    Path recording = Files.createFile(temp.resolve("recording.jfr"));
    Path library = Files.createFile(temp.resolve("libjavaProfiler-dd-tmp123.so"));
    StringBuilder report = new StringBuilder();

    assertTrue(EnvironmentChecker.checkEnvironment(report));

    assertTrue(report.toString().contains("Checking profiler temporary directory: " + temp));
    assertTrue(report.toString().contains("Profiler temporary directory is writable"));
    assertTrue(report.toString().contains("Native library loaded successfully"));
    assertTrue(Files.exists(recording));
    assertTrue(Files.exists(library));
    try (Stream<Path> files = Files.list(temp)) {
      // the files above plus the library scratch directory
      assertEquals(3, files.count());
    }
    verify(builder).withScratchDir(temp.resolve("scratch").toString());
    verify(builder).load();
  }

  @Test
  void reportsUnavailableProfilerTempDir() {
    when(tempManager.getTempDir()).thenThrow(new IllegalStateException("insecure temp directory"));
    StringBuilder report = new StringBuilder();

    assertFalse(EnvironmentChecker.checkEnvironment(report));

    assertTrue(report.toString().contains("Unable to obtain profiler temporary directory."));
    assertTrue(report.toString().contains("insecure temp directory"));
    assertFalse(report.toString().contains("Profiler is ready to be used."));
  }

  @Test
  void reportsNonWritableProfilerTempDir() {
    when(tempManager.getTempDir()).thenReturn(temp.resolve("missing"));
    StringBuilder report = new StringBuilder();

    assertFalse(EnvironmentChecker.checkEnvironment(report));

    assertTrue(
        report.toString().contains("Unable to create test file in " + temp.resolve("missing")));
    assertFalse(report.toString().contains("Profiler is ready to be used."));
  }

  @Test
  void reportsEarlierProfilerLoadFailure() {
    DdprofLibraryLoader.JavaProfilerHolder holder =
        mock(DdprofLibraryLoader.JavaProfilerHolder.class);
    when(holder.isLoadAttempted()).thenReturn(true);
    when(holder.getReasonNotLoaded())
        .thenReturn(new UnsatisfiedLinkError("profiler library could not be loaded"));
    scratchLocations.when(DdprofLibraryLoader::javaProfiler).thenReturn(holder);
    StringBuilder report = new StringBuilder();

    assertFalse(EnvironmentChecker.checkEnvironment(report));

    assertTrue(report.toString().contains("Datadog profiler failed to load earlier in this JVM:"));
    assertTrue(report.toString().contains("profiler library could not be loaded"));
    libraries.verifyNoInteractions();
  }

  @Test
  void waitsForAnInFlightProfilerLoad() throws Exception {
    DdprofLibraryLoader.JavaProfilerHolder holder =
        mock(DdprofLibraryLoader.JavaProfilerHolder.class);
    scratchLocations.when(DdprofLibraryLoader::javaProfiler).thenReturn(holder);
    Thread checker = Thread.currentThread();
    CountDownLatch loading = new CountDownLatch(1);
    AtomicReference<Throwable> loaderFailure = new AtomicReference<>();
    // simulates a profiler component holding the load lock while it loads the library
    Thread loader =
        new Thread(
            () -> {
              try {
                synchronized (DdprofLibraryLoader.class) {
                  loading.countDown();
                  await().until(() -> checker.getState() == Thread.State.BLOCKED);
                  when(holder.isLoadAttempted()).thenReturn(true);
                  when(holder.getReasonNotLoaded())
                      .thenReturn(new UnsatisfiedLinkError("failed while the flare waited"));
                }
              } catch (Throwable t) {
                loaderFailure.set(t);
              }
            });
    loader.start();
    loading.await();
    StringBuilder report = new StringBuilder();

    assertFalse(EnvironmentChecker.checkEnvironment(report));

    loader.join();
    assertNull(loaderFailure.get());
    assertTrue(report.toString().contains("Datadog profiler failed to load earlier in this JVM:"));
    assertTrue(report.toString().contains("failed while the flare waited"));
    libraries.verifyNoInteractions();
  }

  @Test
  void reportsEarlierLibraryFailureOfOtherComponents() {
    DdprofLibraryLoader.JVMAccessHolder holder = mock(DdprofLibraryLoader.JVMAccessHolder.class);
    when(holder.isLoadAttempted()).thenReturn(true);
    when(holder.getReasonNotLoaded())
        .thenReturn(new UnsatisfiedLinkError("jvm access library could not be loaded"));
    scratchLocations.when(DdprofLibraryLoader::jvmAccess).thenReturn(holder);
    when(builder.load()).thenReturn(LibraryLoader.Result.UNAVAILABLE);
    StringBuilder report = new StringBuilder();

    assertFalse(EnvironmentChecker.checkEnvironment(report));

    assertTrue(report.toString().contains("jvm access library could not be loaded"));
    assertFalse(report.toString().contains("An earlier native library load failed"));
  }

  @Test
  void reportsLibraryLoadingFailureAndItsCause() {
    when(builder.load())
        .thenReturn(
            new LibraryLoader.Result(
                false,
                new IOException(
                    "Failed to load Datadog Java profiler library",
                    new UnsatisfiedLinkError("configured library could not be loaded"))));
    StringBuilder report = new StringBuilder();

    assertFalse(EnvironmentChecker.checkEnvironment(report));

    assertTrue(report.toString().contains("configured library could not be loaded"));
    assertFalse(report.toString().contains("Profiler is ready to be used."));
  }

  @Test
  void reportsAnUnavailableLibraryWithoutAnException() {
    when(builder.load()).thenReturn(LibraryLoader.Result.UNAVAILABLE);
    StringBuilder report = new StringBuilder();

    assertFalse(EnvironmentChecker.checkEnvironment(report));

    assertTrue(report.toString().contains("An earlier native library load failed"));
    assertFalse(report.toString().contains("Profiler is ready to be used."));
  }

  @Test
  void skipsScratchDirectoryWhenProfilerIsAlreadyLoaded() throws Exception {
    DdprofLibraryLoader.JavaProfilerHolder holder =
        mock(DdprofLibraryLoader.JavaProfilerHolder.class);
    when(holder.isLoaded()).thenReturn(true);
    scratchLocations.when(DdprofLibraryLoader::javaProfiler).thenReturn(holder);

    StringBuilder report = new StringBuilder();
    assertTrue(EnvironmentChecker.checkEnvironment(report));

    assertTrue(report.toString().contains("Native library loaded successfully"));
    assertFalse(Files.exists(temp.resolve("scratch")));
    libraries.verifyNoInteractions();
  }

  @Test
  void containsUnexpectedLoaderFailuresInTheReport() {
    when(builder.load()).thenThrow(new LinkageError("loader unavailable"));
    StringBuilder report = new StringBuilder();

    assertFalse(EnvironmentChecker.checkEnvironment(report));

    assertTrue(report.toString().contains("loader unavailable"));
    assertFalse(report.toString().contains("Profiler is ready to be used."));
  }

  @Test
  void skipsNativeLibraryLoadingOnNonLinuxPlatforms() {
    os.when(OperatingSystem::isLinux).thenReturn(false);
    StringBuilder report = new StringBuilder();

    assertTrue(EnvironmentChecker.checkEnvironment(report));

    assertTrue(report.toString().contains("Skipping native library check on non-linux platform"));
    assertTrue(report.toString().contains("Profiler temporary directory is writable"));
    libraries.verifyNoInteractions();
  }

  @Test
  void flareUsesTheApplicationProfilerEnvironment() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      new ProfilerFlareReporter().addReportToFlare(zip);
    }

    String report = null;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.getName().equals("profiler_env.txt")) {
          ByteArrayOutputStream content = new ByteArrayOutputStream();
          byte[] buffer = new byte[1024];
          int length;
          while ((length = zip.read(buffer)) != -1) {
            content.write(buffer, 0, length);
          }
          report = new String(content.toByteArray(), StandardCharsets.UTF_8);
        }
      }
    }

    assertNotNull(report);
    assertTrue(report.contains("Native library loaded successfully"));
    verify(builder).load();
  }
}
