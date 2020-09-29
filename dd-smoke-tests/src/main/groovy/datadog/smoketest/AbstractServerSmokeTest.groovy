package datadog.smoketest


import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.PortUtils
import okhttp3.OkHttpClient
import spock.lang.Shared

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

abstract class AbstractServerSmokeTest extends AbstractSmokeTest {

  @Shared
  int httpPort = PortUtils.randomOpenPort()

  @Shared
  File output = createTemporaryFile()

  protected OkHttpClient client = OkHttpUtils.client()

  File createTemporaryFile() {
    return null
  }

  protected Set<String> expectedTraces() {
    return Collections.emptySet()
  }

  def setupSpec() {
    PortUtils.waitForPortToOpen(httpPort, 240, TimeUnit.SECONDS, testedProcess)
  }

  def cleanupSpec() {
    if (null != output) {
      // check the structures written out to the log,
      // and fail the run if anything unexpected was recorded
      verifyLog()
    }
  }

  def verifyLog() {
    BufferedReader reader = new BufferedReader(new FileReader(output))
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
    assert isAcceptable(traceCounts)
  }

  protected boolean isAcceptable(Map<String, AtomicInteger> traceCounts) {
    return assertTraceCounts(expectedTraces(), traceCounts)
  }

  private boolean assertTraceCounts(Set<String> expected, Map<String, AtomicInteger> traceCounts) {
    boolean ok = traceCounts.size() == expected.size()
    if (ok) {
      for (Map.Entry<String, AtomicInteger> entry : traceCounts.entrySet()) {
        ok &= expected.contains(entry.getKey())
        ok &= entry.getValue().get() > 0
      }
    }
    return ok
  }
}
