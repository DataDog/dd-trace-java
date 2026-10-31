package datadog.trace.agent;

import static datadog.trace.agent.test.IntegrationTestUtils.runOnSeparateJvm;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jvmbootstraptest.LogLevelChecker;
import org.junit.jupiter.api.Timeout;
import org.tabletest.junit.TableTest;

@Timeout(30)
class LogLevelTest {

  /* Priority: datadog.slf4j.simpleLogger.defaultLogLevel > dd.trace.debug > DD_TRACE_DEBUG
  1: INFO LOGS
  0: DEBUG Logs
  */

  @TableTest({
    "scenario                                          | traceDebug | slf4jLogLevel | envTraceDebug | expectedExitCode",
    "dd.trace.debug false                              | false      |               |               | 1               ",
    "SLF4J DEBUG && dd.trace.debug is false            | false      | debug         |               | 0               ",
    "dd.trace.debug is false && DD_TRACE_DEBUG is true | false      |               | true          | 1               ",
    "dd.trace.debug is true                            | true       |               |               | 0               ",
    "DD_TRACE_DEBUG is true                            |            |               | true          | 0               ",
    "dd.trace.debug is true && DD_TRACE_DEBUG is false | true       |               | false         | 0               ",
    "SLF4J DEBUG && DD_TRACE_DEBUG is false            |            | debug         | false         | 0               ",
    "SLF4J INFO && DD_TRACE_DEBUG is true              |            | info          | true          | 1               "
  })
  void logLevel(String traceDebug, String slf4jLogLevel, String envTraceDebug, int expectedExitCode)
      throws Exception {
    List<String> jvmArgs = new ArrayList<>();
    if (traceDebug != null) {
      jvmArgs.add("-Ddd.trace.debug=" + traceDebug);
    }
    if (slf4jLogLevel != null) {
      jvmArgs.add("-Ddatadog.slf4j.simpleLogger.defaultLogLevel=" + slf4jLogLevel);
    }
    jvmArgs.add("-Ddd.jmxfetch.enabled=false");
    jvmArgs.add("-Ddd.trace.enabled=false");
    Map<String, String> envVars =
        envTraceDebug == null ? emptyMap() : singletonMap("DD_TRACE_DEBUG", envTraceDebug);

    assertEquals(
        expectedExitCode,
        runOnSeparateJvm(LogLevelChecker.class.getName(), jvmArgs, emptyList(), envVars, true));
  }
}
