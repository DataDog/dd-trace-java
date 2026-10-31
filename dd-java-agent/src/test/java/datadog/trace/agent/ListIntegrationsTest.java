package datadog.trace.agent;

import static datadog.trace.agent.test.IntegrationTestUtils.runOnSeparateJvm;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.bootstrap.AgentJar;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ListIntegrationsTest {

  @Test
  void ddTraceDebugFalse() throws Exception {
    // setup
    ByteArrayOutputStream testOutput = new ByteArrayOutputStream();

    assertEquals(
        0,
        runOnSeparateJvm(
            AgentJar.class.getName(),
            emptyList(),
            singletonList("-li"),
            emptyMap(),
            new PrintStream(testOutput)));
    assertFalse(new String(testOutput.toByteArray()).contains("ERROR"));
  }
}
