package datadog.telemetry;

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED;
import static datadog.trace.api.telemetry.ProductChange.ProductType.APPSEC;
import static datadog.trace.api.telemetry.ProductChange.ProductType.DYNAMIC_INSTRUMENTATION;
import static datadog.trace.api.telemetry.ProductChange.ProductType.PROFILER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.telemetry.api.RequestType;
import datadog.trace.api.Config;
import datadog.trace.api.ConfigOrigin;
import datadog.trace.api.ConfigSetting;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.telemetry.ProductChange.ProductType;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.RequestBody;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.tabletest.junit.TableTest;

/** This test only verifies non-functional specifics that are not covered in TelemetryServiceTest */
@ExtendWith(WithConfigExtension.class)
class TelemetryRequestBodyTest {

  @AfterEach
  void resetProcessTags() {
    ProcessTags.reset(Config.get());
  }

  @Test
  void throwSerializationExceptionInCaseOfJsonNestingProblem() {
    TelemetryRequestBody req = new TelemetryRequestBody(RequestType.APP_STARTED);

    req.beginRequest(false);
    TelemetryRequestBody.SerializationException exception =
        assertThrows(
            TelemetryRequestBody.SerializationException.class, () -> req.beginRequest(false));

    assertEquals("Failed serializing Telemetry begin-request part!", exception.getMessage());
    assertNotNull(exception.getCause());
  }

  @Test
  void throwSerializationExceptionInCaseOfMoreThanOneTopLevelJsonValue() {
    TelemetryRequestBody req = new TelemetryRequestBody(RequestType.APP_STARTED);

    req.beginRequest(false);
    req.endRequest();
    TelemetryRequestBody.SerializationException exception =
        assertThrows(
            TelemetryRequestBody.SerializationException.class, () -> req.beginRequest(false));

    assertEquals("Failed serializing Telemetry begin-request part!", exception.getMessage());
    assertNotNull(exception.getCause());
  }

  @Test
  void writeConfigMustSupportValuesOfBooleanStringNumberAndNull() throws IOException {
    TelemetryRequestBody req =
        new TelemetryRequestBody(RequestType.APP_CLIENT_CONFIGURATION_CHANGE);
    Map<String, Object> map = new HashMap<>();
    map.put("key1", "value1");
    map.put("key2", Double.parseDouble("432.32"));
    map.put("key3", 324);

    req.beginRequest(false);
    // exclude request header to simplify assertion
    drainToString(req);

    req.beginConfiguration();
    List<ConfigSetting> configSettings =
        Arrays.asList(
            ConfigSetting.of("string", "bar", ConfigOrigin.REMOTE),
            ConfigSetting.of("int", 2342, ConfigOrigin.DEFAULT),
            ConfigSetting.of("double", Double.valueOf("123.456"), ConfigOrigin.ENV),
            ConfigSetting.of("map", map, ConfigOrigin.JVM_PROP),
            ConfigSetting.of("list", Arrays.asList("1", "2", 3), ConfigOrigin.DEFAULT),
            // make sure null values are serialized
            ConfigSetting.of("null", null, ConfigOrigin.DEFAULT));
    for (ConfigSetting configSetting : configSettings) {
      req.writeConfiguration(configSetting);
    }
    req.endConfiguration();

    String expectedJson =
        ",\"configuration\":["
            + "{\"name\":\"DD_STRING\",\"value\":\"bar\",\"origin\":\"remote_config\",\"seq_id\":0},"
            + "{\"name\":\"DD_INT\",\"value\":\"2342\",\"origin\":\"default\",\"seq_id\":0},"
            + "{\"name\":\"DD_DOUBLE\",\"value\":\"123.456\",\"origin\":\"env_var\",\"seq_id\":0},"
            + "{\"name\":\"DD_MAP\",\"value\":\"key1:value1,key2:432.32,key3:324\",\"origin\":\"jvm_prop\",\"seq_id\":0},"
            + "{\"name\":\"DD_LIST\",\"value\":\"1,2,3\",\"origin\":\"default\",\"seq_id\":0},"
            + "{\"name\":\"DD_NULL\",\"value\":null,\"origin\":\"default\",\"seq_id\":0}]";
    assertEquals(expectedJson, drainToString(req));
  }

  @Test
  void useEnvironmentVariableForSettingKeys() throws IOException {
    TelemetryRequestBody req =
        new TelemetryRequestBody(RequestType.APP_CLIENT_CONFIGURATION_CHANGE);

    req.beginRequest(false);
    // exclude request header to simplify assertion
    drainToString(req);

    req.beginConfiguration();
    req.writeConfiguration(ConfigSetting.of("this.is.a.key", "value", ConfigOrigin.REMOTE));
    req.endConfiguration();

    assertEquals(
        ",\"configuration\":[{\"name\":\"DD_THIS_IS_A_KEY\",\"value\":\"value\",\"origin\":\"remote_config\",\"seq_id\":0}]",
        drainToString(req));
  }

  @Test
  void addDebugFlag() throws IOException {
    TelemetryRequestBody req = new TelemetryRequestBody(RequestType.APP_STARTED);

    req.beginRequest(true);
    req.endRequest();

    assertTrue(drainToString(req).contains("\"debug\":true"));
  }

  @TableTest({
    "scenario                                          | appsecChange | profilerChange | dynamicInstrumentationChange | appsecEnabled | profilerEnabled | dynamicInstrumentationEnabled",
    "all products changed and enabled                  | true         | true           | true                         | true          | true            | true                         ",
    "all products changed and disabled                 | true         | true           | true                         | false         | false           | false                        ",
    "no product changed                                | false        | false          | false                        | true          | true            | true                         ",
    "only profiler and dynamic instrumentation changed | false        | true           | true                         | true          | true            | true                         ",
    "only appsec and dynamic instrumentation changed   | true         | false          | true                         | true          | true            | true                         ",
    "only appsec and profiler changed                  | true         | true           | false                        | true          | true            | true                         "
  })
  void writeProducts(
      boolean appsecChange,
      boolean profilerChange,
      boolean dynamicInstrumentationChange,
      boolean appsecEnabled,
      boolean profilerEnabled,
      boolean dynamicInstrumentationEnabled)
      throws IOException {
    TelemetryRequestBody req = new TelemetryRequestBody(RequestType.APP_PRODUCT_CHANGE);
    Map<ProductType, Boolean> products = new EnumMap<>(ProductType.class);
    if (appsecChange) {
      products.put(APPSEC, appsecEnabled);
    }
    if (profilerChange) {
      products.put(PROFILER, profilerEnabled);
    }
    if (dynamicInstrumentationChange) {
      products.put(DYNAMIC_INSTRUMENTATION, dynamicInstrumentationEnabled);
    }

    req.beginRequest(false);
    req.writeProducts(products);
    req.endRequest();

    String result = drainToString(req);
    assertEquals(appsecChange, result.contains("\"appsec\":{\"enabled\":" + appsecEnabled + "}"));
    assertEquals(
        profilerChange, result.contains("\"profiler\":{\"enabled\":" + profilerEnabled + "}"));
    assertEquals(
        dynamicInstrumentationChange,
        result.contains(
            "\"dynamic_instrumentation\":{\"enabled\":" + dynamicInstrumentationEnabled + "}"));
  }

  @TableTest({
    "scenario | processTagsEnabled",
    "enabled  | true              ",
    "disabled | false             "
  })
  @SuppressWarnings("unchecked")
  void shouldPropagateProcessTagsWhenEnabled(boolean processTagsEnabled) throws IOException {
    WithConfigExtension.injectSysConfig(
        EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, String.valueOf(processTagsEnabled));
    try {
      ProcessTags.reset(Config.get());
      TelemetryRequestBody req = new TelemetryRequestBody(RequestType.APP_STARTED);

      req.beginRequest(true);
      req.endRequest();

      JsonAdapter<Object> adapter =
          new Moshi.Builder()
              .build()
              .adapter(Types.newParameterizedType(Map.class, String.class, Object.class));
      Map<String, Object> parsed = (Map<String, Object>) adapter.fromJson(drainToString(req));
      Map<String, Object> application = (Map<String, Object>) parsed.get("application");
      Object parsedTags = application.get("process_tags");
      if (processTagsEnabled) {
        assertEquals(ProcessTags.getTagsForSerialization().toString(), parsedTags);
      } else {
        assertNull(parsedTags);
      }
    } finally {
      WithConfigExtension.injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false");
    }
  }

  private static String drainToString(RequestBody body) throws IOException {
    Buffer buf = new Buffer();
    body.writeTo(buf);
    byte[] bytes = new byte[(int) buf.size()];
    buf.read(bytes);
    return new String(bytes);
  }
}
