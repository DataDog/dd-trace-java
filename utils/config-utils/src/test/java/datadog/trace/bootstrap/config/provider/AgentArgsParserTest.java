package datadog.trace.bootstrap.config.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

public class AgentArgsParserTest {

  @Test
  void parsesASingleArgument() {
    String args = "key1=value1";

    Map<String, String> properties = AgentArgsParser.parseAgentArgs(args);

    assertNotNull(properties);
    assertEquals(1, properties.size());
    assertEquals("value1", properties.get("key1"));
  }

  @Test
  void parsesMultipleArguments() {
    String args = "key1=value1,key2=value2";

    Map<String, String> properties = AgentArgsParser.parseAgentArgs(args);

    assertNotNull(properties);
    assertEquals(2, properties.size());
    assertEquals("value2", properties.get("key2"));
  }

  @Test
  void returnsNullForNullString() {
    Map<String, String> properties = AgentArgsParser.parseAgentArgs(null);

    assertNull(properties);
  }

  @Test
  void returnsNullForEmptyString() {
    Map<String, String> properties = AgentArgsParser.parseAgentArgs("");

    assertNull(properties);
  }

  @Test
  void returnsNullForMalformedString() {
    Map<String, String> properties = AgentArgsParser.parseAgentArgs("key=value,,,==");

    assertNull(properties);
  }

  @Test
  void parsesArgumentWithSpaces() {
    String args = "key=value with spaces";

    Map<String, String> properties = AgentArgsParser.parseAgentArgs(args);

    assertNotNull(properties);
    assertEquals(1, properties.size());
    assertEquals("value with spaces", properties.get("key"));
  }
}
