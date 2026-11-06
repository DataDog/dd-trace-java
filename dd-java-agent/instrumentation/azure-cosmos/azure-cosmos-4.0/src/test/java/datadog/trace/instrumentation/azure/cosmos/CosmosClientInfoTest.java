package datadog.trace.instrumentation.azure.cosmos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.azure.cosmos.ConnectionMode;
import org.junit.jupiter.api.Test;

class CosmosClientInfoTest {

  @Test
  void connectionMode() {
    assertEquals("gateway", CosmosClientInfo.connectionMode(ConnectionMode.GATEWAY));
    assertEquals("direct", CosmosClientInfo.connectionMode(ConnectionMode.DIRECT));
    assertEquals("other", CosmosClientInfo.connectionMode(null));
  }
}
