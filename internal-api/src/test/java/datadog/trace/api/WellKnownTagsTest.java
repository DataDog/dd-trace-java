package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WellKnownTagsTest {

  @Test
  void wellKnownTagsDoesNotModifyInputs() {
    WellKnownTags wellKnownTags =
        new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");

    assertEquals("runtimeid", wellKnownTags.getRuntimeId().toString());
    assertEquals("hostname", wellKnownTags.getHostname().toString());
    assertEquals("env", wellKnownTags.getEnv().toString());
    assertEquals("service", wellKnownTags.getService().toString());
    assertEquals("version", wellKnownTags.getVersion().toString());
    assertEquals("language", wellKnownTags.getLanguage().toString());
  }

  @Test
  void toStringIncludesAllFields() {
    WellKnownTags wellKnownTags =
        new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");

    assertEquals(
        "WellKnownTags{runtimeId=runtimeid, hostname=hostname, env=env,"
            + " service=service, version=version, language=language}",
        wellKnownTags.toString());
  }
}
