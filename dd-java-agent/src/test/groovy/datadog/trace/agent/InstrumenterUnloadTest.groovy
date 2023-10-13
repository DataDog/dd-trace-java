package datadog.trace.agent

import datadog.trace.agent.test.IntegrationTestUtils
import jvmbootstraptest.UnloadingChecker
import spock.lang.Specification
import spock.lang.Timeout

@Timeout(30)
class InstrumenterUnloadTest extends Specification {

  private static final String DEFAULT_LOG_LEVEL = "debug"
  private static final String API_KEY = "01234567890abcdef123456789ABCDEF"

  // Run test using forked jvm
  def "instrumenter and muzzle classes can be unloaded after use"() {
    setup:
    def testOutput = new ByteArrayOutputStream()

    when:
    int returnCode = IntegrationTestUtils.runOnSeparateJvm(UnloadingChecker.getName()
      , [
        "-verbose:class",
        "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=$DEFAULT_LOG_LEVEL"
      ] as String[]
      , "" as String[]
      , ["DD_API_KEY": API_KEY]
      , new PrintStream(testOutput))

    int unloadCount = 0
    new ByteArrayInputStream((testOutput.toByteArray())).eachLine {
      System.out.println(it)
      if (it =~ /(?i)unload.* datadog.trace.instrumentation./) {
        unloadCount++
      }
    }

    then:
    returnCode == 0
    unloadCount > 0
  }
}
