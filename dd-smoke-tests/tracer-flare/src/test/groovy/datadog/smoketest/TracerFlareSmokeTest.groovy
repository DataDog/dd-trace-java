package datadog.smoketest

import spock.lang.Shared

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * This smoke test can be extended with additional configurations of the agent and assertions on the files
 * expected to be present in the resulting flare(s). Checking individual file contents should be done a per-reporter
 * basis.
 *
 * For DD Employees - if you update this file alongside changes to an existing reporter or the creation of a new one,
 * please also document it in the
 * <a href="https://datadoghq.atlassian.net/wiki/spaces/APMINT/pages/3389554943/Java+Tracer+Flare">Tracer Flare Wiki</a>
 */
class TracerFlareSmokeTest extends AbstractSmokeTest {


  // Time in seconds after which flare is triggered.
  // We delay the profiler start on Oracle JDK 8, so increase the wait time to at least 25s. We've seen ≤20 seconds
  // cause rare test flakes.
  private static final int FLARE_TRIGGER_SECONDS = 25
  // Number of processes to run in parallel for testing
  private static final int NUMBER_OF_PROCESSES = 3

  protected int numberOfProcesses() {
    NUMBER_OF_PROCESSES
  }

  @Shared
  final flareDirs = [
    File.createTempDir("flare-test-profiling-enabled-", ""),
    File.createTempDir("flare-test-profiling-disabled-", ""),
    File.createTempDir("flare-test-profiling-with-override-", "")
  ]

  def cleanupSpec() {
    flareDirs.each { dir ->
      if (dir.exists()) {
        dir.deleteDir()
      }
    }
  }

  @Override
  ProcessBuilder createProcessBuilder(int processIndex) {
    String jarPath = System.getProperty("datadog.smoketest.tracer-flare.jar.path")
    File flareDir = flareDirs[processIndex]

    def command = [javaPath()]

    switch (processIndex) {
      case 0:
      // Process 0: Profiling enabled (default)
        command.addAll(defaultJavaProperties)
        break
      case 1:
      // Process 1: Profiling disabled
        def filteredProperties = defaultJavaProperties.findAll { prop ->
          !prop.startsWith("-Ddd.profiling.")
        }
        command.addAll(filteredProperties)
        command.add("-Ddd.profiling.enabled=false")
        break
      case 2:
      // Process 2: Profiling enabled with template override
        command.addAll(defaultJavaProperties)
      // Create a temp file with the override content
        def tempOverrideFile = File.createTempFile("test-override-", ".jfp")
        tempOverrideFile.deleteOnExit()
        tempOverrideFile.text = "datadog.ExceptionSample#enabled=false" // Arbitrary event choice
        command.add("-Ddd.profiling.jfr-template-override-file=" + tempOverrideFile.absolutePath)
        break
    }

    // Configure flare generation
    command.addAll([
      "-Ddd.triage.report.trigger=${FLARE_TRIGGER_SECONDS}s",
      "-Ddd.triage.report.dir=${flareDir.absolutePath}",
      "-Ddd.trace.debug=true",
      // Enable debug to get more files
      '-jar',
      jarPath
    ] as String[])

    new ProcessBuilder(command).tap {
      it.directory(new File(buildDirectory))
    }
  }

  // Core files that should always be present
  private static final CORE_FILES = [
    "flare_info.txt",
    "tracer_version.txt",
    "initial_config.txt",
    "dynamic_config.txt",
    "jvm_args.txt",
    "classpath.txt",
    "library_path.txt",
    "threads.txt",
    // Should be present with triage=true
    // Files from CoreTracer
    "tracer_health.txt",
    "span_metrics.txt",
    // Files from InstrumenterFlare (always registered)
    "instrumenter_state.txt",
    "instrumenter_metrics.txt",
    // Files from DI
    "dynamic_instrumentation.txt"
  ] as Set<String>

  // Optional files that may or may not be present depending on conditions
  private static final OPTIONAL_FILES = [
    "boot_classpath.txt",
    // Only if JVM supports it (Java 8)
    "tracer.log",
    // Only if logging is configured
    "tracer_begin.log",
    // Alternative log format
    "tracer_end.log",
    // Alternative log format
    "flare_errors.txt",
    // Only if there were errors
    "pending_traces.txt",
    // Only if there were traces pending transmission
    "profiling_template_override.jfp",
    // Only if template override is configured
    "profiler_log.txt"
    // Only if there are any profiler issues reported
  ] as Set<String>

  // Profiling-related files
  private static final PROFILING_FILES = ["profiler_config.txt", "profiler_env.txt"
    // Only if profiling is enabled
  ] as Set<String>

  // Flare file naming pattern constants
  private static final String FLARE_FILE_PREFIX = "dd-java-flare-"
  private static final String FLARE_FILE_EXTENSION = ".zip"

  /**
   * Checks if a filename matches the expected flare file pattern
   */
  private static boolean isFlareFile(String fileName) {
    fileName.startsWith(FLARE_FILE_PREFIX) && fileName.endsWith(FLARE_FILE_EXTENSION)
  }

  def "tracer generates flare with profiling enabled (default)"() {
    when:
    // Wait for flare file to be created using filesystem watcher
    // The flare is triggered after FLARE_TRIGGER_SECONDS, plus some write time
    def flareFile = waitForFlareFile(flareDirs[0])
    def zipContents = extractZipContents(flareFile)

    then:
    // Verify core files are present
    CORE_FILES.each { file ->
      assert file in zipContents : "Missing required core file: ${file}"
    }

    PROFILING_FILES.each { file ->
      assert (file in zipContents) : "Didn't find profiling file '${file}' when profiling is enabled"
    }

    // Check for unexpected files and fail if found
    validateNoUnexpectedFiles(zipContents, CORE_FILES + OPTIONAL_FILES + PROFILING_FILES)
  }

  def "tracer generates flare with profiling disabled"() {
    when:
    // Wait for flare file to be created independently for process 1
    // Each test should be independent and not rely on timing from other tests
    def flareFile = waitForFlareFile(flareDirs[1])
    def zipContents = extractZipContents(flareFile)

    then:
    // Verify core files are present
    CORE_FILES.each { file ->
      assert file in zipContents : "Missing required core file: ${file}"
    }

    // Verify NO profiling files are present when profiling is disabled
    PROFILING_FILES.each { file ->
      assert !(file in zipContents) : "Found profiling file '${file}' when profiling is disabled"
    }

    // Check for unexpected files and fail if found (profiling files excluded from expected)
    validateNoUnexpectedFiles(zipContents, CORE_FILES + OPTIONAL_FILES)
  }

  def "tracer generates flare with profiling template override"() {
    when:
    // Wait for flare file to be created for process 2 (profiling with template override)
    def flareFile = waitForFlareFile(flareDirs[2])
    def zipContents = extractZipContents(flareFile)

    then:
    // Verify core files are present
    CORE_FILES.each { file ->
      assert file in zipContents : "Missing required core file: ${file}"
    }

    PROFILING_FILES.each { file ->
      assert (file in zipContents) : "Didn't find profiling file '${file}' when profiling is enabled"
    }

    // Verify no template override file when not configured (the typical scenario)
    assert "profiling_template_override.jfp" in zipContents : "Didn't find profiling_template_override.jfp when override was configured"

    // Check for unexpected files and fail if found
    validateNoUnexpectedFiles(zipContents, CORE_FILES + OPTIONAL_FILES + PROFILING_FILES)
  }

  private static void validateNoUnexpectedFiles(Set<String> zipContents, Set<String> expectedFiles) {
    def unexpectedFiles = zipContents - expectedFiles
    assert !unexpectedFiles : "Found unexpected files in flare: ${unexpectedFiles}"
  }

  private static Set<String> extractZipContents(File zipFile) {
    def fileNames = []

    zipFile.withInputStream { stream ->
      new ZipInputStream(stream).withCloseable { zis ->
        ZipEntry entry
        while ((entry = zis.nextEntry) != null) {
          if (!entry.directory) {
            fileNames << entry.name
          }
          zis.closeEntry()
        }
      }
    }

    fileNames
  }

  /**
   * Waits for a flare file to be created in the specified directory using filesystem watching.
   * 
   * @param flareDir The directory to watch for flare files
   * @param timeoutSeconds Maximum time to wait for the file
   * @return The created flare file
   * @throws AssertionError if no flare file is created within the timeout
   */
  private static File waitForFlareFile(File flareDir, int timeoutSeconds = FLARE_TRIGGER_SECONDS + 5) {
    Path dirPath = flareDir.toPath()
    WatchService watchService = FileSystems.getDefault().newWatchService()

    try {
      def existingFile = findFlareFileIfExists(flareDir)
      if (existingFile) {
        return existingFile
      }

      dirPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE)
      long deadlineMillis = System.currentTimeMillis() + (timeoutSeconds * 1000)

      while (System.currentTimeMillis() < deadlineMillis) {
        long remainingMillis = deadlineMillis - System.currentTimeMillis()
        if (remainingMillis <= 0) {
          break
        }

        WatchKey key = watchService.poll(remainingMillis, TimeUnit.MILLISECONDS)
        if (key == null) {
          existingFile = findFlareFileIfExists(flareDir)
          if (existingFile) {
            return existingFile
          }
          break
        }

        for (WatchEvent<?> event : key.pollEvents()) {
          WatchEvent<Path> pathEvent = (WatchEvent<Path>) event
          Path fileName = pathEvent.context()

          if (isFlareFile(fileName.toString())) {
            return new File(flareDir, fileName.toString())
          }
        }

        boolean valid = key.reset()
        if (!valid) {
          throw new AssertionError("Watch directory ${flareDir} is no longer accessible")
        }
      }

      existingFile = findFlareFileIfExists(flareDir)
      if (existingFile) {
        return existingFile
      }

      throw new AssertionError("No flare file created in ${flareDir} within ${timeoutSeconds} seconds")
    } finally {
      watchService.close()
    }
  }

  /**
   * Attempts to find an existing flare file in the directory.
   * Returns null if no flare file exists.
   */
  private static File findFlareFileIfExists(File flareDir) {
    def flareFiles = flareDir.listFiles({ File dir, String name ->
      isFlareFile(name)
    } as FilenameFilter)

    return flareFiles?.size() > 0 ? flareFiles.first() : null
  }
}
