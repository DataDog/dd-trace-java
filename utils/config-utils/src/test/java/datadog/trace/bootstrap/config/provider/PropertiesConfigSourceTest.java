package datadog.trace.bootstrap.config.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.test.util.DDJavaSpecification;
import java.util.Properties;
import org.junit.jupiter.api.Test;

public class PropertiesConfigSourceTest extends DDJavaSpecification {

  @Test
  void testNull() {
    assertThrows(AssertionError.class, () -> new PropertiesConfigSource(null, true));
  }

  @Test
  void configPulledFromProperties() {
    Properties props = new Properties();
    props.put("abc", "def");
    props.put("dd.abc", "xyz");
    PropertiesConfigSource source = new PropertiesConfigSource(props, false);

    assertEquals("def", source.get("abc"));
    assertEquals("xyz", source.get("dd.abc"));
    assertNull(source.get("missing"));
  }

  @Test
  void configPulledFromPropertiesWithPrefix() {
    Properties props = new Properties();
    props.put("abc", "def");
    props.put("dd.abc", "xyz");
    PropertiesConfigSource source = new PropertiesConfigSource(props, true);

    assertEquals("xyz", source.get("abc"));
    assertNull(source.get("dd.abc"));
    assertNull(source.get("missing"));
  }
}
