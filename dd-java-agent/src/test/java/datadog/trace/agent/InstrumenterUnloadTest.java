package datadog.trace.agent;

import static datadog.trace.agent.test.IntegrationTestUtils.runOnSeparateJvm;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonMap;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.regex.Pattern;
import jvmbootstraptest.UnloadingChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class InstrumenterUnloadTest {

  private static final String DEFAULT_LOG_LEVEL = "debug";
  private static final String API_KEY = "01234567890abcdef123456789ABCDEF";
  private static final Pattern CANARY_UNLOAD = Pattern.compile("unload.*Canary", CASE_INSENSITIVE);
  private static final Pattern INSTRUMENTATION_UNLOAD =
      Pattern.compile("unload.* datadog.trace.instrumentation.", CASE_INSENSITIVE);

  // Run test using forked jvm
  @Test
  void instrumenterAndMuzzleClassesCanBeUnloadedAfterUse() throws Exception {
    // setup
    ByteArrayOutputStream testOutput = new ByteArrayOutputStream();

    // when
    int returnCode =
        runOnSeparateJvm(
            UnloadingChecker.class.getName(),
            asList(
                "-verbose:class",
                "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=" + DEFAULT_LOG_LEVEL),
            emptyList(),
            singletonMap("DD_API_KEY", API_KEY),
            new PrintStream(testOutput));

    boolean canaryUnloaded = false;
    int unloadedInstrumentationCount = 0;
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(new ByteArrayInputStream(testOutput.toByteArray())))) {
      String line;
      while ((line = reader.readLine()) != null) {
        System.out.println(line);
        if (CANARY_UNLOAD.matcher(line).find()) {
          canaryUnloaded = true;
        }
        if (INSTRUMENTATION_UNLOAD.matcher(line).find()) {
          unloadedInstrumentationCount++;
        }
      }
    }

    if (!canaryUnloaded) {
      System.out.println("WARNING: Canary class was not unloaded!");
    }

    // then
    assertEquals(0, returnCode);
    // skip check if we couldn't even unload our Canary class, as that
    // indicates full GC didn't happen enough to trigger any unloading
    if (canaryUnloaded) {
      assertTrue(unloadedInstrumentationCount > 0);
    }
  }
}
