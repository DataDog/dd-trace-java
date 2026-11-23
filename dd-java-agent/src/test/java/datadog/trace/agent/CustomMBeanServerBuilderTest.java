package datadog.trace.agent;

import static datadog.trace.agent.test.IntegrationTestUtils.runOnSeparateJvm;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import jvmbootstraptest.MBeanServerBuilderSetter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class CustomMBeanServerBuilderTest {

  private static final String DEFAULT_LOG_LEVEL = "debug";

  // Run all tests using forked jvm so we try different JMX settings
  @Test
  void jmxFetchStartsUpInPremainWithNoCustomMBeanServerBuilderSet() throws Exception {
    assertEquals(0, runMBeanServerBuilderSetter());
  }

  @Test
  void jmxFetchStartupIsDelayedEvenIfConfiguredMBeanServerBuilderOnSystemClasspath()
      throws Exception {
    assertEquals(
        0,
        runMBeanServerBuilderSetter(
            "-Djavax.management.builder.initial=jvmbootstraptest.CustomMBeanServerBuilder"));
  }

  @Test
  void jmxFetchStartupIsDelayedWithJavaxManagementBuilderInitialSysprop() throws Exception {
    assertEquals(
        0,
        runMBeanServerBuilderSetter(
            "-Djavax.management.builder.initial=jvmbootstraptest.MissingMBeanServerBuilder"));
  }

  @Test
  void jmxFetchStartupIsDelayedWithTracerCustomJmxBuilderSetting() throws Exception {
    assertEquals(0, runMBeanServerBuilderSetter("-Ddd.app.customjmxbuilder=true"));
  }

  @Test
  void jmxFetchStartsUpInPremainForcedByCustomJmxBuilderFalse() throws Exception {
    assertEquals(
        0,
        runMBeanServerBuilderSetter(
            "-Ddd.app.customjmxbuilder=false",
            "-Djavax.management.builder.initial=jvmbootstraptest.CustomMBeanServerBuilder"));
  }

  private static int runMBeanServerBuilderSetter(String... extraJvmArgs) throws Exception {
    List<String> jvmArgs =
        new ArrayList<>(
            asList(
                "-Ddd.jmxfetch.enabled=true",
                "-Ddd.jmxfetch.start-delay=0",
                "-Ddd.jmxfetch.refresh-beans-period=1",
                "-Ddd.profiling.enabled=true",
                "-Ddd.instrumentation.telemetry.enabled=false",
                "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=" + DEFAULT_LOG_LEVEL));
    jvmArgs.addAll(asList(extraJvmArgs));
    return runOnSeparateJvm(
        MBeanServerBuilderSetter.class.getName(), jvmArgs, emptyList(), emptyMap(), true);
  }
}
