package datadog.smoketest

import spock.lang.Shared

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

  // Time in seconds after which flare is triggered
  private static final int FLARE_TRIGGER_SECONDS = 10
  // Additional buffer time to ensure flare is written to disk
  private static final int FLARE_WRITE_BUFFER_SECONDS = 5
  // Number of processes to run in parallel for testing
  private static final int NUMBER_OF_PROCESSES = 2

  protected int numberOfProcesses() {
    NUMBER_OF_PROCESSES
  }

  @Shared
  final flareDirs = [
    File.createTempDir("flare-test-profiling-enabled-", ""),
    File.createTempDir("flare-test-profiling-disabled-", "")
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

    if (processIndex == 0) {
      // Process 0: Profiling enabled (default)
      command.addAll(defaultJavaProperties)
    } else {
      // Process 1: Profiling disabled
      def filteredProperties = defaultJavaProperties.findAll { prop ->
        !prop.startsWith("-Ddd.profiling.")
      }
      command.addAll(filteredProperties)
      command.add("-Ddd.profiling.enabled=false")
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
    "pending_traces.txt"      // Only if there were traces pending transmission
  ] as Set<String>

  // Profiling-related files
  private static final PROFILING_FILES = [
    "profiler_config.txt",
    // Only if profiling is enabled
    "profiling_template_override.jfp"  // Only if template override is configured
  ] as Set<String>

  def "tracer generates flare with profiling enabled (default)"() {
    given:
    // Wait for flare to be generated (triggered after FLARE_TRIGGER_SECONDS + buffer time)
    Thread.sleep((FLARE_TRIGGER_SECONDS + FLARE_WRITE_BUFFER_SECONDS) * 1000)

    when:
    // Find the generated flare file from process 0
    def flareFile = findFlareFile(flareDirs[0])
    def zipContents = extractZipContents(flareFile)

    then:
    // Verify core files are present
    CORE_FILES.each { file ->
      assert file in zipContents : "Missing required core file: ${file}"
    }

    // Verify profiling files are present (profiling is enabled in defaultJavaProperties)
    assert "profiler_config.txt" in zipContents : "Missing profiler_config.txt when profiling is enabled"

    // Check for unexpected files and fail if found
    validateNoUnexpectedFiles(zipContents, CORE_FILES + OPTIONAL_FILES + PROFILING_FILES)
  }

  def "tracer generates flare with profiling disabled"() {
    when:
    // Find the generated flare file from process 1
    // The flare should already be generated from the wait in the first test
    def flareFile = findFlareFile(flareDirs[1])
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

  private static File findFlareFile(File flareDir) {
    def flareFiles = flareDir.listFiles({ File dir, String name ->
      name.startsWith("dd-java-flare-") && name.endsWith(".zip")
    } as FilenameFilter)
    assert flareFiles.size() == 1 : "Expected exactly one flare file, found: ${flareFiles.size()}"
    flareFiles.first()
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
}
