package datadog.trace.lambda;

import static datadog.trace.lambda.LambdaAwsAccessKeyId.ENV_ACCESS_KEY_ID;
import static datadog.trace.lambda.LambdaAwsAccessKeyId.ENV_INITIALIZATION_TYPE;
import static datadog.trace.lambda.LambdaAwsAccessKeyId.ENV_MICROVM_IMAGE_ARN;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.ConfigCollector;
import datadog.trace.api.ConfigSetting;
import datadog.trace.core.DDCoreJavaSpecification;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LambdaAwsAccessKeyIdTest extends DDCoreJavaSpecification {

  static final String KEY_ID = "ASIAEXAMPLE000000001";
  static final String CANARY_SECRET = "canary-secret-6f1d0c";
  static final String CANARY_TOKEN = "canary-token-93b2aa";

  @BeforeEach
  @AfterEach
  void resetCache() {
    LambdaAwsAccessKeyId.reset();
  }

  @ParameterizedTest
  @ValueSource(strings = {KEY_ID, "AKIAEXAMPLE000000001", "ASIA0123456789ABCDEF"})
  void acceptsTemporaryAndLongTermKeyIds(String keyId) {
    assertEquals(keyId, LambdaAwsAccessKeyId.get(env(keyId, null)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "ASIAEXAMPLE00000001", // 15 characters after the prefix
        "ASIAEXAMPLE0000000001", // 17 characters after the prefix
        "asiaexample000000001", // lower case
        "ABIAEXAMPLE000000001", // unknown prefix
        "ASIAEXAMPLE00000000-", // symbol
        "ASIAEXAMPLE000000001\n", // trailing newline
        " ASIAEXAMPLE000000001", // leading space
        "ASIAEXAMPLE000000001ASIAEXAMPLE000000001"
      })
  void rejectsMalformedValues(String value) {
    assertNull(LambdaAwsAccessKeyId.get(env(value, null)));
  }

  @Test
  void rejectsMissingValue() {
    assertNull(LambdaAwsAccessKeyId.get(env(null, null)));
  }

  @Test
  void readsOnlyTheAccessKeyIdAndTheSnapshotMarkers() {
    List<String> read = new ArrayList<>();
    Map<String, String> vars = new HashMap<>();
    vars.put(ENV_ACCESS_KEY_ID, KEY_ID);
    vars.put("AWS_SECRET_ACCESS_KEY", CANARY_SECRET);
    vars.put("AWS_SESSION_TOKEN", CANARY_TOKEN);

    String value =
        LambdaAwsAccessKeyId.get(name -> {
          read.add(name);
          return vars.get(name);
        });

    assertEquals(KEY_ID, value);
    assertEquals(asList(ENV_INITIALIZATION_TYPE, ENV_MICROVM_IMAGE_ARN, ENV_ACCESS_KEY_ID), read);
  }

  @Test
  void readsOncePerExecutionEnvironment() {
    List<String> read = new ArrayList<>();
    Function<String, String> first = recording(read, env(KEY_ID, null));
    Function<String, String> rotated = recording(read, env("ASIAEXAMPLE000000002", null));

    assertEquals(KEY_ID, LambdaAwsAccessKeyId.get(first));
    assertEquals(KEY_ID, LambdaAwsAccessKeyId.get(rotated));
    assertEquals(asList(ENV_INITIALIZATION_TYPE, ENV_MICROVM_IMAGE_ARN, ENV_ACCESS_KEY_ID), read);
  }

  @Test
  void cachesAnInvalidValueToo() {
    List<String> read = new ArrayList<>();

    assertNull(LambdaAwsAccessKeyId.get(recording(read, env("bad", null))));
    assertNull(LambdaAwsAccessKeyId.get(recording(read, env(KEY_ID, null))));
    assertEquals(3, read.size());
  }

  @Test
  void neverReadsTheKeyOnSnapStart() {
    List<String> read = new ArrayList<>();

    assertNull(LambdaAwsAccessKeyId.get(recording(read, env(KEY_ID, "snap-start"))));
    assertEquals(singletonList(ENV_INITIALIZATION_TYPE), read);
  }

  @Test
  void neverReadsTheKeyOnAMicroVmImage() {
    List<String> read = new ArrayList<>();
    Map<String, String> vars = new HashMap<>();
    vars.put(ENV_ACCESS_KEY_ID, KEY_ID);
    vars.put(ENV_MICROVM_IMAGE_ARN, "arn:aws:lambda:us-east-1:123456789012:microvm-image:x");

    assertNull(LambdaAwsAccessKeyId.get(recording(read, vars::get)));
    assertEquals(asList(ENV_INITIALIZATION_TYPE, ENV_MICROVM_IMAGE_ARN), read);
  }

  @Test
  void neverReportsTheValueToConfigurationTelemetry() {
    injectEnvConfig(ENV_ACCESS_KEY_ID, KEY_ID, false);
    ConfigCollector.get().collect(); // drain what earlier config builds reported

    assertEquals(KEY_ID, LambdaAwsAccessKeyId.get());

    for (Map<String, ConfigSetting> settings : ConfigCollector.get().collect().values()) {
      for (ConfigSetting setting : settings.values()) {
        assertNotEquals(ENV_ACCESS_KEY_ID, setting.key);
        assertNotEquals(KEY_ID, String.valueOf(setting.value));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"on-demand", "provisioned-concurrency", "lambda-managed-instances"})
  void readsTheKeyOnOtherInitializationTypes(String initializationType) {
    assertEquals(KEY_ID, LambdaAwsAccessKeyId.get(env(KEY_ID, initializationType)));
  }

  @Test
  void readsTheProcessEnvironment() {
    injectEnvConfig(ENV_ACCESS_KEY_ID, KEY_ID, false);
    injectEnvConfig("AWS_SECRET_ACCESS_KEY", CANARY_SECRET, false);

    assertEquals(KEY_ID, LambdaAwsAccessKeyId.get());
  }

  static Function<String, String> env(String accessKeyId, String initializationType) {
    Map<String, String> vars = new HashMap<>();
    vars.put(ENV_ACCESS_KEY_ID, accessKeyId);
    vars.put(ENV_INITIALIZATION_TYPE, initializationType);
    return vars::get;
  }

  private static Function<String, String> recording(
      List<String> read, Function<String, String> env) {
    return name -> {
      read.add(name);
      return env.apply(name);
    };
  }
}
