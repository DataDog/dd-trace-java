package datadog.smoketest


import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.PortUtils
import okhttp3.OkHttpClient
import spock.lang.Shared

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
      PortUtils.waitForPortToOpen(port, 240, TimeUnit.SECONDS, process)
    }
  }

  def cleanupSpec() {
    (0..<numberOfProcesses).each { idx ->
      File outputFile
      if (null != (outputFile = outputs[idx])) {
        // check the structures written out to the log,
        // and fail the run if anything unexpected was recorded
        verifyLog(idx, outputFile)
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
}
