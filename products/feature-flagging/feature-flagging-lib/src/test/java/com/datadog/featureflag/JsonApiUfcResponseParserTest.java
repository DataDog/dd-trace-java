package com.datadog.featureflag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
  void rejectsNonStringJsonApiType() throws Exception {
    assertNull(parse("{\"data\":{\"type\":null,\"attributes\":" + emptyConfig() + "}}"));
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

  @Test
  void observeFullEvaluationDataDefaultsToFalseWhenAbsent() throws Exception {
    final ServerConfiguration configuration = parse(wrap(emptyConfig()));
    assertNotNull(configuration);
    assertFalse(configuration.observeFullEvaluationData);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void observeFullEvaluationDataParsesExplicitValue(final boolean value) throws Exception {
    final ServerConfiguration configuration =
        parse(wrap(configWithObserveFullEvaluationData(value)));
    assertNotNull(configuration);
    assertEquals(value, configuration.observeFullEvaluationData);
  }

  @Test
  void observeFullEvaluationDataRejectsExplicitNull() {
    // An explicit null for this boolean is malformed input. Parsing rejects the whole
    // configuration, which is the fail-closed outcome we want: full evaluation data is never
    // observed off the back of a malformed config. Callers (AgentlessConfigurationSource and the
    // remote-config poller) swallow the failure and keep the last-known-good config, and the
    // gateway defaults to the privacy-preserving behaviour when no valid config was dispatched.
    // Servers send true/false or omit the field; null is not a value they emit.
    assertThrows(Exception.class, () -> parse(wrap(configWithNullObserveFullEvaluationData())));
  }

  private static ServerConfiguration parse(final String json) throws Exception {
    return JsonApiUfcResponseParser.INSTANCE.parse(json.getBytes(UTF_8));
  }

  private static String wrap(final String attributes) {
    return "{\"data\":{\"type\":\"universal-flag-configuration\",\"attributes\":"
        + attributes
        + "}}";
  }

  private static String configWithObserveFullEvaluationData(final boolean value) {
    return "{"
        + "\"createdAt\":\"2024-04-17T19:40:53.716Z\","
        + "\"observeFullEvaluationData\":"
        + value
        + ","
        + "\"environment\":{\"name\":\"Test\"},"
        + "\"flags\":{}"
        + "}";
  }

  private static String configWithNullObserveFullEvaluationData() {
    return "{"
        + "\"createdAt\":\"2024-04-17T19:40:53.716Z\","
        + "\"observeFullEvaluationData\":null,"
        + "\"environment\":{\"name\":\"Test\"},"
        + "\"flags\":{}"
        + "}";
  }

  private static String emptyConfig() {
    return "{"
        + "\"createdAt\":\"2024-04-17T19:40:53.716Z\","
        + "\"environment\":{\"name\":\"Test\"},"
        + "\"flags\":{}"
        + "}";
  }
}
