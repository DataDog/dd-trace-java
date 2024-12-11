package datadog.json;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JsonStructureTest {
  // toString is only for debug purpose and has no behavior to cover
  @Test
  void coverToString() {
    SafeJsonStructure safeJsonStructure = new SafeJsonStructure();
    assertNotNull(safeJsonStructure.toString());
    safeJsonStructure.beginObject();
    safeJsonStructure.endObject();
    assertNotNull(safeJsonStructure.toString());
  }

  // Lenient is a stub and has no behavior to cover
  @Test
  void lenientStructure() {
    LenientJsonStructure lenientJsonStructure = new LenientJsonStructure();
    lenientJsonStructure.beginObject();
    assertTrue(lenientJsonStructure.objectStarted());
    lenientJsonStructure.endObject();
    lenientJsonStructure.beginArray();
    assertTrue(lenientJsonStructure.arrayStarted());
    lenientJsonStructure.endArray();
    assertDoesNotThrow(lenientJsonStructure::addName);
    assertDoesNotThrow(lenientJsonStructure::addValue);
  }
}
