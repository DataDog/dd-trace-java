package com.datadog.featureflag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class JsonApiUfcResponseParserTest {

  @Test
  void parsesJsonApiMembersInAnyOrder() throws Exception {
    final ServerConfiguration configuration =
        parse(
            "{"
                + "\"meta\":{\"ignored\":true},"
                + "\"data\":{"
                + "\"attributes\":"
                + emptyConfig()
                + ",\"ignored\":true,"
                + "\"type\":\"universal-flag-configuration\""
                + "}"
                + "}");

    assertNotNull(configuration);
    assertEquals("Test", configuration.environment.name);
    assertTrue(configuration.flags.isEmpty());
  }

  @Test
  void rejectsRawUfc() throws Exception {
    assertNull(parse(emptyConfig()));
  }

  @Test
  void rejectsUnexpectedJsonApiType() throws Exception {
    assertNull(parse("{\"data\":{\"type\":\"other-type\",\"attributes\":" + emptyConfig() + "}}"));
  }

  @Test
  void rejectsConfigurationWithoutFlags() throws Exception {
    assertNull(
        parse(
            "{\"data\":{"
                + "\"type\":\"universal-flag-configuration\","
                + "\"attributes\":{\"environment\":{\"name\":\"Test\"}}"
                + "}}"));
  }

  @Test
  void rejectsNonObjectData() throws Exception {
    assertNull(parse("{\"data\":[]}"));
  }

  @Test
  void rejectsTrailingJson() {
    assertThrows(
        IOException.class,
        () ->
            parse(
                "{\"data\":{\"type\":\"universal-flag-configuration\",\"attributes\":"
                    + emptyConfig()
                    + "}}{}"));
  }

  private static ServerConfiguration parse(final String json) throws Exception {
    return JsonApiUfcResponseParser.INSTANCE.parse(json.getBytes(UTF_8));
  }

  private static String emptyConfig() {
    return "{"
        + "\"createdAt\":\"2024-04-17T19:40:53.716Z\","
        + "\"environment\":{\"name\":\"Test\"},"
        + "\"flags\":{}"
        + "}";
  }
}
