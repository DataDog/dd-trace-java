package datadog.trace.agent;

import static datadog.trace.agent.test.IntegrationTestUtils.runOnSeparateJvm;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jvmbootstraptest.LogManagerSetter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class CustomLogManagerTest {

  private static final String DEFAULT_LOG_LEVEL = "debug";

  // Run all tests using forked jvm because groovy has already set the global log manager
  @Test
  void agentServicesStartsUpInPremainWithNoCustomLogManagerSet() throws Exception {
    assertEquals(0, runLogManagerSetter(emptyMap()));
  }

  @Test
  void agentServicesStartupIsDelayedEvenIfConfiguredLogManagerOnSystemClasspath() throws Exception {
    assertEquals(
        0,
        runLogManagerSetter(
            emptyMap(), "-Djava.util.logging.manager=jvmbootstraptest.CustomLogManager"));
  }

  @Test
  void agentServicesStartupIsDelayedWithJavaUtilLoggingManagerSysprop() throws Exception {
    assertEquals(
        0,
        runLogManagerSetter(
            emptyMap(), "-Djava.util.logging.manager=jvmbootstraptest.MissingLogManager"));
  }

  @Test
  void agentServicesStartupDelayedWithTracerCustomLogManagerSetting() throws Exception {
    assertEquals(0, runLogManagerSetter(emptyMap(), "-Ddd.app.customlogmanager=true"));
  }

  @Test
  void agentServicesStartupDelayedWithJbossHomeEnvironmentVariable() throws Exception {
    assertEquals(
        0, runLogManagerSetter(singletonMap("JBOSS_HOME", "/"), "-Ddd.app.customjmxbuilder=false"));
  }

  @Test
  void agentServicesStartupInPremainForcedByCustomLogManagerFalse() throws Exception {
    assertEquals(
        0,
        runLogManagerSetter(
            singletonMap("JBOSS_HOME", "/"),
            "-Ddd.app.customlogmanager=false",
            "-Ddd.app.customjmxbuilder=false",
            "-Djava.util.logging.manager=jvmbootstraptest.CustomLogManager"));
  }

  private static int runLogManagerSetter(Map<String, String> envVars, String... extraJvmArgs)
      throws Exception {
    List<String> jvmArgs =
        new ArrayList<>(
            asList(
                "-Ddd.jmxfetch.enabled=true",
                "-Ddd.jmxfetch.start-delay=0",
                "-Ddd.jmxfetch.refresh-beans-period=1",
                "-Ddd.profiling.enabled=true",
                "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=" + DEFAULT_LOG_LEVEL));
    jvmArgs.addAll(asList(extraJvmArgs));
    return runOnSeparateJvm(LogManagerSetter.class.getName(), jvmArgs, emptyList(), envVars, true);
  }
}
