package datadog.trace.bootstrap.config.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.tabletest.junit.TableTest;

public class AgentArgsParserTest {

  @TableTest({
    "scenario             | args                    | size | key  | expectedValue    ",
    "single argument      | key1=value1             | 1    | key1 | value1           ",
    "multiple arguments   | key1=value1,key2=value2 | 2    | key2 | value2           ",
    "argument with spaces | key=value with spaces   | 1    | key  | value with spaces"
  })
  void parsesArguments(String args, int size, String key, String expectedValue) {
    Map<String, String> properties = AgentArgsParser.parseAgentArgs(args);

    assertNotNull(properties);
    assertEquals(size, properties.size());
    assertEquals(expectedValue, properties.get(key));
  }

  @TableTest({
    "scenario     | args          ",
    "null string  |               ",
    "empty string | ''            ",
    "malformed    | key=value,,,=="
  })
  void returnsNullForInvalidArguments(String args) {
    assertNull(AgentArgsParser.parseAgentArgs(args));
  }
}
