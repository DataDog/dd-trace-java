package com.datadog.featureflag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.Capabilities;
import datadog.remoteconfig.ConfigurationDeserializer;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tabletest.junit.TableTest;

@ExtendWith(MockitoExtension.class)
class RemoteConfigServiceImplTest {

  @Mock private FeatureFlaggingGateway.ConfigListener listener;
  @Captor private ArgumentCaptor<ConfigurationDeserializer> deserializerCaptor;

  @AfterEach
  void cleanup() {
    FeatureFlaggingGateway.removeConfigListener(listener);
  }

  @Test
  void testNewConfigReceived() throws Exception {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final SharedCommunicationObjects sco = mock(SharedCommunicationObjects.class);
    when(sco.configurationPoller(any(Config.class))).thenReturn(poller);
    FeatureFlaggingGateway.addConfigListener(listener);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(sco, Config.get());

    service.init();

    verify(poller).addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller).addListener(eq(Product.FFE_FLAGS), deserializerCaptor.capture(), eq(service));

    final ServerConfiguration config = deserializer().deserialize(emptyConfig().getBytes(UTF_8));
    service.accept("test", config, mock(PollingRateHinter.class));

    verify(listener).accept(any(ServerConfiguration.class));

    service.close();

    verify(poller).removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller).removeListeners(Product.FFE_FLAGS);
  }

  @Test
  void skipsMalformedFlagAllocationsAndKeepsValidFlag() throws Exception {
    final ServerConfiguration config =
        deserialize(
            "{"
                + "\"createdAt\":\"2024-04-17T19:40:53.716Z\","
                + "\"format\":\"SERVER\","
                + "\"environment\":{\"name\":\"Test\"},"
                + "\"flags\":{"
                + "\"malformed-flag\":{"
                + "\"key\":\"malformed-flag\","
                + "\"enabled\":true,"
                + "\"variationType\":\"STRING\","
                + "\"variations\":{\"on\":{\"key\":\"on\",\"value\":\"on\"}},"
                + "\"allocations\":\"this-is-not-a-list\""
                + "},"
                + "\"valid-flag\":{"
                + "\"key\":\"valid-flag\","
                + "\"enabled\":true,"
                + "\"variationType\":\"STRING\","
                + "\"variations\":{\"expected\":{\"key\":\"expected\",\"value\":\"expected\"}},"
                + "\"allocations\":[{"
                + "\"key\":\"default-allocation\","
                + "\"rules\":[],"
                + "\"splits\":[{\"variationKey\":\"expected\",\"shards\":[]}],"
                + "\"doLog\":true"
                + "}]"
                + "}"
                + "}"
                + "}");

    assertNotNull(config);
    assertFalse(config.flags.containsKey("malformed-flag"));
    assertTrue(config.flags.containsKey("valid-flag"));
    assertEquals("expected", config.flags.get("valid-flag").variations.get("expected").value);
  }

  @Test
  void ignoresUnknownTopLevelFields() throws Exception {
    final ServerConfiguration config =
        deserialize(
            "{"
                + "\"createdAt\":\"2024-04-17T19:40:53.716Z\","
                + "\"format\":\"SERVER\","
                + "\"environment\":{\"name\":\"Test\"},"
                + "\"segments\":{\"new-schema-key\":{\"ignored\":true}},"
                + "\"flags\":{}"
                + "}");

    assertNotNull(config);
    assertEquals("2024-04-17T19:40:53.716Z", config.createdAt);
    assertEquals("SERVER", config.format);
    assertNotNull(config.environment);
    assertEquals("Test", config.environment.name);
    assertTrue(config.flags.isEmpty());
  }

  @Test
  void parsesAllocationWindowDatesAsDateFieldsWithInstantAccessors() throws Exception {
    final ServerConfiguration config =
        deserialize(
            "{"
                + "\"createdAt\":\"2024-04-17T19:40:53.716Z\","
                + "\"format\":\"SERVER\","
                + "\"environment\":{\"name\":\"Test\"},"
                + "\"flags\":{"
                + "\"dated-flag\":{"
                + "\"key\":\"dated-flag\","
                + "\"enabled\":true,"
                + "\"variationType\":\"STRING\","
                + "\"variations\":{\"expected\":{\"key\":\"expected\",\"value\":\"expected\"}},"
                + "\"allocations\":[{"
                + "\"key\":\"dated-allocation\","
                + "\"rules\":[],"
                + "\"startAt\":\"2023-01-01T01:00:00+01:00\","
                + "\"endAt\":\"2023-01-02T00:00:00Z\","
                + "\"splits\":[{\"variationKey\":\"expected\",\"shards\":[]}],"
                + "\"doLog\":true"
                + "}]"
                + "}"
                + "}"
                + "}");

    final Allocation allocation = config.flags.get("dated-flag").allocations.get(0);
    assertEquals(Date.class, Allocation.class.getField("startAt").getType());
    assertEquals(Date.class, Allocation.class.getField("endAt").getType());
    assertEquals(Instant.parse("2023-01-01T00:00:00Z"), allocation.startAt.toInstant());
    assertEquals(Instant.parse("2023-01-02T00:00:00Z"), allocation.endAt.toInstant());
    assertEquals(Instant.parse("2023-01-01T00:00:00Z"), allocation.startAtInstant());
    assertEquals(Instant.parse("2023-01-02T00:00:00Z"), allocation.endAtInstant());
  }

  @Test
  void skipsUnknownOperatorFlagAndKeepsValidFlag() throws Exception {
    final ServerConfiguration config =
        deserialize(
            "{"
                + "\"createdAt\":\"2024-04-17T19:40:53.716Z\","
                + "\"format\":\"SERVER\","
                + "\"environment\":{\"name\":\"Test\"},"
                + "\"flags\":{"
                + "\"operator-grease-flag\":{"
                + "\"key\":\"operator-grease-flag\","
                + "\"enabled\":true,"
                + "\"variationType\":\"STRING\","
                + "\"variations\":{\"trap\":{\"key\":\"trap\",\"value\":\"trap\"}},"
                + "\"allocations\":[{"
                + "\"key\":\"grease-allocation\","
                + "\"rules\":[{\"conditions\":[{"
                + "\"attribute\":\"country\","
                + "\"operator\":\"not-a-real-operator\","
                + "\"value\":\"anything\""
                + "}]}],"
                + "\"splits\":[{\"variationKey\":\"trap\",\"shards\":[]}],"
                + "\"doLog\":true"
                + "}]"
                + "},"
                + "\"valid-flag\":{"
                + "\"key\":\"valid-flag\","
                + "\"enabled\":true,"
                + "\"variationType\":\"STRING\","
                + "\"variations\":{\"expected\":{\"key\":\"expected\",\"value\":\"expected\"}},"
                + "\"allocations\":[{"
                + "\"key\":\"default-allocation\","
                + "\"rules\":[],"
                + "\"splits\":[{\"variationKey\":\"expected\",\"shards\":[]}],"
                + "\"doLog\":true"
                + "}]"
                + "}"
                + "}"
                + "}");

    assertNotNull(config);
    assertFalse(config.flags.containsKey("operator-grease-flag"));
    assertTrue(config.flags.containsKey("valid-flag"));
    assertEquals("expected", config.flags.get("valid-flag").variations.get("expected").value);
  }

  @Test
  void flagMapAdapterFactoryOnlyCreatesFlagMapAdapterForFlagMapType() {
    final Moshi moshi = moshi();
    final Type flagsType = Types.newParameterizedType(Map.class, String.class, Flag.class);

    final JsonAdapter<?> adapter =
        RemoteConfigServiceImpl.FlagMapAdapter.FACTORY.create(flagsType, emptySet(), moshi);

    assertNotNull(adapter);
    assertTrue(adapter instanceof RemoteConfigServiceImpl.FlagMapAdapter);
    assertNull(
        RemoteConfigServiceImpl.FlagMapAdapter.FACTORY.create(String.class, emptySet(), moshi));
    assertNull(
        RemoteConfigServiceImpl.FlagMapAdapter.FACTORY.create(
            flagsType, singleton(mock(Annotation.class)), moshi));
  }

  @Test
  void allowsNullFlagMap() throws Exception {
    final ServerConfiguration config =
        deserialize(
            "{"
                + "\"createdAt\":\"2024-04-17T19:40:53.716Z\","
                + "\"format\":\"SERVER\","
                + "\"environment\":{\"name\":\"Test\"},"
                + "\"flags\":null"
                + "}");

    assertNotNull(config);
    assertNull(config.flags);
  }

  @Test
  void skipsNullFlagAndKeepsValidFlag() throws Exception {
    final ServerConfiguration config =
        deserialize(
            "{"
                + "\"createdAt\":\"2024-04-17T19:40:53.716Z\","
                + "\"format\":\"SERVER\","
                + "\"environment\":{\"name\":\"Test\"},"
                + "\"flags\":{"
                + "\"null-flag\":null,"
                + "\"valid-flag\":{"
                + "\"key\":\"valid-flag\","
                + "\"enabled\":true,"
                + "\"variationType\":\"STRING\","
                + "\"variations\":{\"expected\":{\"key\":\"expected\",\"value\":\"expected\"}},"
                + "\"allocations\":[{"
                + "\"key\":\"default-allocation\","
                + "\"rules\":[],"
                + "\"splits\":[{\"variationKey\":\"expected\",\"shards\":[]}],"
                + "\"doLog\":true"
                + "}]"
                + "}"
                + "}"
                + "}");

    assertNotNull(config);
    assertFalse(config.flags.containsKey("null-flag"));
    assertTrue(config.flags.containsKey("valid-flag"));
    assertEquals("expected", config.flags.get("valid-flag").variations.get("expected").value);
  }

  @Test
  void flagMapAdapterIsReadOnly() {
    final RemoteConfigServiceImpl.FlagMapAdapter adapter =
        new RemoteConfigServiceImpl.FlagMapAdapter(moshi().adapter(Flag.class));

    assertThrows(
        UnsupportedOperationException.class,
        () -> adapter.toJson(mock(JsonWriter.class), emptyMap()));
  }

  @TableTest({
    "scenario                       | value                            | expectedInstant           ",
    "utc second                     | '2023-01-01T00:00:00Z'           | '2023-01-01T00:00:00Z'    ",
    "utc end of year                | '2023-12-31T23:59:59Z'           | '2023-12-31T23:59:59Z'    ",
    "leap day                       | '2024-02-29T12:00:00Z'           | '2024-02-29T12:00:00Z'    ",
    "millisecond precision          | '2023-01-01T00:00:00.000Z'       | '2023-01-01T00:00:00Z'    ",
    "three fractional digits        | '2023-06-15T14:30:45.123Z'       | '2023-06-15T14:30:45.123Z'",
    "six fractional digits          | '2023-06-15T14:30:45.123456Z'    | '2023-06-15T14:30:45.123Z'",
    "six fractional digits distinct | '2023-06-15T14:30:45.235982Z'    | '2023-06-15T14:30:45.235Z'",
    "nine fractional digits         | '2023-06-15T14:30:45.123456789Z' | '2023-06-15T14:30:45.123Z'",
    "one fractional digit           | '2023-06-15T14:30:45.1Z'         | '2023-06-15T14:30:45.100Z'",
    "two fractional digits          | '2023-06-15T14:30:45.12Z'        | '2023-06-15T14:30:45.120Z'",
    "positive offset                | '2023-01-01T01:00:00+01:00'      | '2023-01-01T00:00:00Z'    ",
    "negative offset                | '2023-01-01T00:00:00-05:00'      | '2023-01-01T05:00:00Z'    ",
    "date only                      | '2023-01-01'                     |                           ",
    "invalid                        | 'invalid-date'                   |                           ",
    "empty string                   | ''                               |                           ",
    "not a date                     | 'not-a-date'                     |                           ",
    "slash date                     | '2023/01/01T00:00:00Z'           |                           ",
    "null                           |                                  |                           "
  })
  void testDateParsing(final String value, final String expectedInstant) throws Exception {
    final JsonReader reader = mock(JsonReader.class);
    when(reader.nextString()).thenReturn(value);
    final RemoteConfigServiceImpl.DateAdapter adapter = new RemoteConfigServiceImpl.DateAdapter();

    final Date parsed = adapter.fromJson(reader);
    if (expectedInstant == null) {
      assertNull(parsed);
    } else {
      assertNotNull(parsed);
      assertEquals(expectedInstant, parsed.toInstant().toString());
    }
  }

  @Test
  void testParsingOnlyAdapter() {
    final RemoteConfigServiceImpl.DateAdapter adapter = new RemoteConfigServiceImpl.DateAdapter();

    assertThrows(
        UnsupportedOperationException.class,
        () -> adapter.toJson(mock(JsonWriter.class), Date.from(Instant.EPOCH)));
  }

  @SuppressWarnings("unchecked")
  private ConfigurationDeserializer<ServerConfiguration> deserializer() {
    return deserializerCaptor.getValue();
  }

  private static ServerConfiguration deserialize(final String json) throws Exception {
    return RemoteConfigServiceImpl.UniversalFlagConfigDeserializer.INSTANCE.deserialize(
        json.getBytes(UTF_8));
  }

  private static Moshi moshi() {
    return new Moshi.Builder().add(Date.class, new RemoteConfigServiceImpl.DateAdapter()).build();
  }

  private static String emptyConfig() {
    return "{"
        + "\"createdAt\":\"2024-04-17T19:40:53.716Z\","
        + "\"format\":\"SERVER\","
        + "\"environment\":{\"name\":\"Test\"},"
        + "\"flags\":{}"
        + "}";
  }
}
