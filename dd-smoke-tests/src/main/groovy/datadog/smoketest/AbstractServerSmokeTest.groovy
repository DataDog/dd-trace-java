package datadog.smoketest


import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.test.util.Flaky
import okhttp3.OkHttpClient
import spock.lang.Shared

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assumptions.assumeTrue

abstract class AbstractServerSmokeTest extends AbstractSmokeTest {

  @Shared
  protected int[] httpPorts = (0..<numberOfProcesses).collect {
    PortUtils.randomOpenPort()
  }

  // Here for backwards compatibility with single process case
  @Shared
  int httpPort = httpPorts[0]

  @Shared
  protected File[] outputs = (0..<numberOfProcesses).collect { idx ->
    def file = createTemporaryFile(idx)
    if (file != null) {
      if (file.exists()) {
        file.delete()
      } else {
        file.getParentFile().mkdirs()
      }
    }
    return file
  }

  // Here for backwards compatibility with single process case
  @Shared
  protected File output = outputs[0]

  protected OkHttpClient client = OkHttpUtils.client()

  protected File createTemporaryFile() {
    return null
  }

  protected File createTemporaryFile(int processIndex) {
    if (processIndex > 0) {
      throw new IllegalArgumentException("Override createTemporaryFile(int processIndex) for multi process tests")
    }
    return createTemporaryFile()
  }

  // Here for backwards compatibility with single process case
  protected Set<String> expectedTraces() {
    return Collections.emptySet()
  }

  protected Set<String> expectedTraces(int processIndex) {
    if (processIndex > 0) {
      throw new IllegalArgumentException("Override expectedTraces(int processIndex) for multi process tests")
    }
    return expectedTraces()
  }

  def setupSpec() {
    (0..<numberOfProcesses).each { idx ->
      def port = httpPorts[idx]
      def process = testedProcesses[idx]

      try {
        PortUtils.waitForPortToOpen(port, 240, TimeUnit.SECONDS, process)
      } catch ( Exception e ) {
        throw new RuntimeException(e.getMessage() + " - log file " + logFilePaths[idx], e)
      }
    }
  }

  def cleanupSpec() {
    (0..<numberOfProcesses).each { idx ->
      File outputFile
      if (null != (outputFile = outputs[idx])) {
        // check the structures written out to the log,
        // and fail the run if anything unexpected was recorded
        try {
          verifyLog(idx, outputFile)
        } catch (FileNotFoundException e) {
          if (testedProcesses[idx].isAlive()) {
            throw e
          }
          def exitCode = testedProcesses[idx].exitValue()
          if (exitCode == 0) {
            throw e
          } else {
            def logFile = logFilePaths[idx]
            // highlight when process exited abnormally, since that may have contributed
            // to the log verification failure
            throw new RuntimeException(
            "Server process exited abnormally - exit code: ${exitCode}; check log file: ${logFile}", e)
          }
        }
      }
    }
  }

  def verifyLog(int processIndex, File logOutput) {
    BufferedReader reader = new BufferedReader(new FileReader(logOutput))
    Map<String, AtomicInteger> traceCounts = new HashMap<>()
    try {
      String line = reader.readLine()
      while (null != line) {
        traceCounts.computeIfAbsent(line, {
          new AtomicInteger()
        }).incrementAndGet()
        line = reader.readLine()
      }
    } finally {
      reader.close()
    }
    assert isAcceptable(processIndex, traceCounts)
  }

  protected boolean isAcceptable(int processIndex, Map<String, AtomicInteger> traceCounts) {
    // If expectedTraces returns an interpolated GString, then the map lookup will fail,
    // so coerce them to proper String instances
    def expected = expectedTraces(processIndex).collect { String.valueOf(it) }.toSet()
    def remaining = assertTraceCounts(expected, traceCounts)
    assert remaining.toList() == [] : "Encountered traces: " + traceCounts
    return remaining.isEmpty()
  }

  protected Set<String> assertTraceCounts(Set<String> expected, Map<String, AtomicInteger> traceCounts) {
    Set<String> remaining = expected.collect().toSet()
    for (Map.Entry<String, AtomicInteger> entry : traceCounts.entrySet()) {
      if (expected.contains(entry.getKey()) && entry.getValue().get() > 0) {
        remaining.remove(entry.getKey())
      }
    }
    return remaining
  }

  @RunLast
  void 'receive telemetry app-started'() {
    when:
    assumeTrue(testTelemetry())
    waitForTelemetryCount(1)

    then:
    telemetryMessages.size() >= 1
    Object msg = telemetryMessages.get(0)
    msg['request_type'] == 'app-started'
  }

  List<String> expectedTelemetryDependencies() {
    []
  }

  @Flaky("Possible reasons: tests are too fast, the waiting mechanism is not robust enough; somehow too much telemetry is produced.")
  @RunLast
  @SuppressWarnings('UnnecessaryBooleanExpression')
  void 'receive telemetry app-dependencies-loaded'() {
    when:
    assumeTrue(testTelemetry())
    // app-started + 3 message-batch
    waitForTelemetryCount(4)
    waitForTelemetryFlat { it.get('request_type') == 'app-dependencies-loaded' }

    then: 'received some dependencies'
    def dependenciesLoaded = telemetryFlatMessages.findAll { it.get('request_type') == 'app-dependencies-loaded' }
    def dependencies = []
    dependenciesLoaded.each {
      def payload = it.get('payload') as Map<String, Object>
      dependencies.addAll(payload.get('dependencies'))
    }
    dependencies.size() > 0

    Set<String> dependencyNames = dependencies.collect {
      def dependency = it as Map<String, Object>
      dependency.get('name') as String
    }.toSet()

    and: 'received tracer dependencies'
    // Not exhaustive list of tracer dependencies.
    Set<String> missingDependencyNames = ['com.github.jnr:jnr-ffi', 'net.bytebuddy:byte-buddy-agent',].toSet()
    missingDependencyNames.removeAll(dependencyNames) || true
    missingDependencyNames.isEmpty()

    and: 'received application dependencies'
    Set<String> missingExtraDependencyNames = expectedTelemetryDependencies().toSet()
    missingExtraDependencyNames.removeAll(dependencyNames) || true
    missingExtraDependencyNames.isEmpty()
  }
}
