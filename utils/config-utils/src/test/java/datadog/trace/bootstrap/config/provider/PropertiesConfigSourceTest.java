package datadog.trace.bootstrap.config.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.test.util.DDJavaSpecification;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

public class PropertiesConfigSourceTest extends DDJavaSpecification {

  @Test
  void throwsWhenPropertiesAreNull() {
    assertThrows(AssertionError.class, () -> new PropertiesConfigSource(null, true));
  }

  @TableTest({
    "scenario    | usePrefix | abc | ddAbc | missing",
    "no prefix   | false     | def | xyz   |        ",
    "with prefix | true      | xyz |       |        "
  })
  void configPulledFromProperties(boolean usePrefix, String abc, String ddAbc, String missing) {
    Properties props = new Properties();
    props.put("abc", "def");
    props.put("dd.abc", "xyz");
    PropertiesConfigSource source = new PropertiesConfigSource(props, usePrefix);

    assertEquals(abc, source.get("abc"));
    assertEquals(ddAbc, source.get("dd.abc"));
    assertEquals(missing, source.get("missing"));
  }
}
