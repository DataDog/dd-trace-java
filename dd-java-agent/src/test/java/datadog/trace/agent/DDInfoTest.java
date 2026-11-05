package datadog.trace.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.trace.api.DDTraceApiInfo;
import datadog.trace.core.DDTraceCoreInfo;
import org.junit.jupiter.api.Test;

class DDInfoTest {

  @Test
  void infoAccessibleFromApi() {
    assertEquals(DDTraceApiInfo.VERSION, DDTraceCoreInfo.VERSION);

    assertNotNull(DDTraceApiInfo.VERSION);
    assertNotEquals("", DDTraceApiInfo.VERSION);
    assertNotEquals("unknown", DDTraceApiInfo.VERSION);
    assertNotNull(DDTraceCoreInfo.VERSION);
    assertNotEquals("", DDTraceCoreInfo.VERSION);
    assertNotEquals("unknown", DDTraceCoreInfo.VERSION);
  }
}
