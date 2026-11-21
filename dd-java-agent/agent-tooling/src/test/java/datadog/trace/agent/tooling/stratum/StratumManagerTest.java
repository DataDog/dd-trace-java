package datadog.trace.agent.tooling.stratum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.Pair;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class StratumManagerTest extends DDJavaSpecification {

  private static final String REGISTER_JSP_CLASS =
      "src/test/resources/datadog.trace.agent.tooling.stratum/register_jsp.class";
  private static final String REGISTER_JSP_CLASS_NAME = "org.apache.jsp.register_jsp";

  @Test
  void analyzeClass() throws IOException {
    // given
    StratumManager stratumManager = new StratumManager(1000, limit -> {});
    byte[] data = Files.readAllBytes(Paths.get(REGISTER_JSP_CLASS));

    // when
    stratumManager.analyzeClass(data);

    // then
    Stratum result = stratumManager.get(REGISTER_JSP_CLASS_NAME);
    assertNotNull(result);
    Pair<String, Integer> inputLine = result.getInputLine(216);
    assertEquals(70, inputLine.getRight());
    assertEquals("register.jsp", result.getSourceFile(inputLine.getLeft()));
  }

  @Test
  void limitReached() throws IOException {
    // setup
    StratumManager newStratumManager = new StratumManager(1, limit -> {});
    byte[] data = Files.readAllBytes(Paths.get(REGISTER_JSP_CLASS));

    // when
    newStratumManager.analyzeClass(data);

    // then
    Stratum result = newStratumManager.get(REGISTER_JSP_CLASS_NAME);
    assertNotNull(result);
    assertEquals(1, newStratumManager.map.size());
    assertTrue(newStratumManager.map.isLimitReached());

    // when
    newStratumManager.analyzeClass(new byte[0]);

    // then
    assertEquals(1, newStratumManager.map.size());
    assertTrue(newStratumManager.map.isLimitReached());
  }
}
