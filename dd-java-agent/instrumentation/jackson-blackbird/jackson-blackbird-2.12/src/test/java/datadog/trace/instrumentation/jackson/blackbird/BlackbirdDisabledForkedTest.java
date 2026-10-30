package datadog.trace.instrumentation.jackson.blackbird;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import org.junit.jupiter.api.Test;

class BlackbirdDisabledForkedTest extends AbstractInstrumentationTest {

  @Test
  void mappersAreUntouchedByDefault() {
    assertFalse(BlackbirdRegistrar.hasAccessorModule(new ObjectMapper()));
    assertFalse(BlackbirdRegistrar.hasAccessorModule(JsonMapper.builder().build()));
  }
}
