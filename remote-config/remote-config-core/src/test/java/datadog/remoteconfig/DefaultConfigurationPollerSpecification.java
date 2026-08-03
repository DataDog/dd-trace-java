package datadog.remoteconfig;

import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.ClientState.ConfigState.APPLY_STATE_ERROR;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cafe.cryptography.ed25519.Ed25519PrivateKey;
import cafe.cryptography.ed25519.Ed25519PublicKey;
import cafe.cryptography.ed25519.Ed25519Signature;
import com.squareup.moshi.Moshi;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import datadog.trace.util.AgentTaskScheduler;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.stubbing.OngoingStubbing;

@WithConfig(key = "rc.targets.key.id", value = "TEST_KEY_ID")
@WithConfig(key = "service", value = "my_service")
@WithConfig(key = "env", value = "my_env")
@WithConfig(key = "remote_config.integrity_check.enabled", value = "true")
class DefaultConfigurationPollerSpecification extends DDJavaSpecification {
  private static final HttpUrl URL = HttpUrl.get("https://example.com/v0.7/config");
  private static final Request REQUEST = new Request.Builder().url("https://example.com").build();
  private static final int DEFAULT_POLL_PERIOD = 5000;
  private static final String KEY_ID = "TEST_KEY_ID";
  private static final Ed25519PrivateKey PRIVATE_KEY =
      Ed25519PrivateKey.generate(new SecureRandom());
  private static final Ed25519PublicKey PUBLIC_KEY = PRIVATE_KEY.derivePublic();

  private static final Moshi MOSHI = new Moshi.Builder().build();

  private final OkHttpClient okHttpClient = mock(OkHttpClient.class);
  private final AgentTaskScheduler scheduler = mock(AgentTaskScheduler.class);

  @SuppressWarnings("unchecked")
  private final AgentTaskScheduler.Scheduled<ConfigurationPoller> scheduled =
      mock(AgentTaskScheduler.Scheduled.class);

  private final Call call = mock(Call.class);

  private DefaultConfigurationPoller poller;
  private AgentTaskScheduler.Task<ConfigurationPoller> task;
  private Request request;
  private Supplier<String> configUrlSupplier = URL::toString;

  private Response buildOKResponse(String bodyStr) {
    ResponseBody body = ResponseBody.create(MediaType.get("application/json"), bodyStr);
    return new Response.Builder()
        .request(REQUEST)
        .protocol(Protocol.HTTP_1_1)
        .message("OK")
        .body(body)
        .code(200)
        .build();
  }

  @BeforeEach
  void setup() {
    // value derived from the randomly generated keypair, so it cannot be a @WithConfig literal
    injectSysConfig("dd.rc.targets.key", new BigInteger(1, PUBLIC_KEY.toByteArray()).toString(16));
    poller =
        new DefaultConfigurationPoller(
            Config.get(), "0.0.0", "", "", () -> configUrlSupplier.get(), okHttpClient, scheduler);
  }

  // ----- Tests -----

  @Test
  void issuesNoRequestIfThereAreNoSubscriptions() {
    start();

    task.run(poller);
    verify(okHttpClient, never()).newCall(any());

    poller.addListener(
        Product.ASM_DD,
        deserializerThrowing("should not be called"),
        (configKey, config, hinter) -> {});
    poller.removeListeners(Product.ASM_DD);
    task.run(poller);
    verify(okHttpClient, never()).newCall(any());

    poller.stop();
    verify(scheduled).cancel();
  }

  @Test
  void issuesRequestIfThereIsASubscription() throws IOException {
    ConfigurationChangesTypedListener<Object> listener = typedListener();

    poller.addListener(Product.ASM_DD, parseDeserializer(), listener);
    start();

    stubHttp(buildOKResponse(SAMPLE_RESP_BODY));
    task.run(poller);

    verify(listener).accept(any(), any(), any());

    String json = bodyJson();
    assertThatJson(json).node("cached_target_files").isAbsent();
    assertThatJson(json).node("client.client_tracer.app_version").isEqualTo("");
    assertThatJson(json).node("client.client_tracer.env").isEqualTo("my_env");
    assertThatJson(json).node("client.client_tracer.language").isEqualTo("java");
    assertThatJson(json).node("client.client_tracer.runtime_id").isString().isNotEmpty();
    assertThatJson(json).node("client.client_tracer.service").isEqualTo("my_service");
    assertThatJson(json).node("client.client_tracer.tracer_version").isEqualTo("0.0.0");
    assertThatJson(json).node("client.id").isString().hasSize(36);
    assertThatJson(json).node("client.is_tracer").isEqualTo(true);
    assertThatJson(json).node("client.products").isArray().containsExactly("ASM_DD");
    assertThatJson(json).node("client.state.config_states").isArray().isEmpty();
    assertThatJson(json).node("client.state.has_error").isEqualTo(false);
    assertThatJson(json).node("client.state.root_version").isEqualTo(1);
    assertThatJson(json).node("client.state.targets_version").isEqualTo(0);
  }

  @Test
  void issuesNoRequestIfTheConfigUrlSupplierReturnsNull() {
    ConfigurationDeserializer<Object> deserializer = deserializerMock();
    ConfigurationChangesTypedListener<Object> listener = typedListener();
    configUrlSupplier = () -> null;

    poller.addListener(Product.ASM_DD, deserializer, listener);
    start();

    task.run(poller);

    verify(okHttpClient, never()).newCall(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void onceTheSupplierProvidesAUrlItIsNotCalledAnymore() throws IOException {
    ConfigurationDeserializer<Object> deserializer = deserializerMock();
    ConfigurationChangesTypedListener<Object> listener = typedListener();
    configUrlSupplier = mock(Supplier.class);
    when(configUrlSupplier.get()).thenReturn(URL.toString());

    poller.addListener(Product.ASM_DD, deserializer, listener);
    start();

    when(deserializer.deserialize(any())).thenReturn(true);
    stubHttp(buildOKResponse(SAMPLE_RESP_BODY), buildOKResponse(SAMPLE_RESP_BODY));
    task.run(poller);
    task.run(poller);

    verify(configUrlSupplier, times(1)).get();
    verify(okHttpClient, times(2)).newCall(any());
    verify(deserializer, times(1)).deserialize(any());
    verify(listener, times(1)).accept(any(), any(), any());
  }

  @Test
  void handleProductListenersPerConfig() throws IOException {
    ConfigurationDeserializer<Object> deserializer = deserializerMock();
    ConfigurationChangesTypedListener<Object> activationListener = typedListener();
    ConfigurationChangesTypedListener<Object> sampleRateListener = typedListener();

    String activationKey = "datadog/2/ASM_FEATURES/asm_features_activation/config";
    String sampleRateKey = "datadog/2/ASM_FEATURES/api_security/sample_rate";

    String respBody =
        toJson(
            map(
                "client_configs", list(activationKey, sampleRateKey),
                "roots", list(),
                "target_files",
                    list(
                        map("path", activationKey, "raw", b64("{\"asm\":{\"enabled\":true}}")),
                        map(
                            "path",
                            sampleRateKey,
                            "raw",
                            b64("{\"api_security\": {\"request_sample_rate\": 0.1}"))),
                "targets",
                    signAndBase64EncodeTargets(
                        map(
                            "signed",
                            map(
                                "expires",
                                "2022-09-17T12:49:15Z",
                                "spec_version",
                                "1.0.0",
                                "targets",
                                map(
                                    activationKey,
                                        map(
                                            "custom", map("v", 1),
                                            "hashes",
                                                map(
                                                    "sha256",
                                                    "159658ab85be7207761a4111172b01558394bfc74a1fe1d314f2023f7c656db"),
                                            "length", 24),
                                    sampleRateKey,
                                        map(
                                            "custom", map("v", 1),
                                            "hashes",
                                                map(
                                                    "sha256",
                                                    "bc898b7eb75d9fd0ddee1c1a556bc3c528dd41382950aa86e48816f792d01494"),
                                            "length", 45)),
                                "version",
                                1)))));

    String noConfigs = withClientConfigs(SAMPLE_RESP_BODY, list());

    poller.addListener(
        Product.ASM_FEATURES, "asm_features_activation", deserializer, activationListener);
    poller.addListener(Product.ASM_FEATURES, "api_security", deserializer, sampleRateListener);
    start();

    when(deserializer.deserialize(any())).thenReturn(true);
    stubHttp(buildOKResponse(respBody), buildOKResponse(noConfigs));

    task.run(poller); // apply
    task.run(poller); // remove all configurations

    // 2 deserializations on apply; accept called once per run (apply + remove) on each listener
    verify(deserializer, times(2)).deserialize(any());
    verify(activationListener, times(2)).accept(eq(activationKey), any(), any());
    verify(sampleRateListener, times(2)).accept(eq(sampleRateKey), any(), any());
  }

  @Test
  void processingHappensForAllListeners() throws IOException {
    ConfigurationDeserializer<Object> deserializer = deserializerMock();
    List<ConfigurationChangesTypedListener<Object>> listeners = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      listeners.add(typedListener());
    }

    String respBody =
        toJson(
            map(
                "client_configs",
                    list(
                        "datadog/2/ASM_FEATURES/asm_features_activation/config",
                        "foo/ASM_DD/bar/config",
                        "foo/ASM/bar/config",
                        "foo/ASM_DATA/bar/config",
                        "foo/LIVE_DEBUGGING/bar/config"),
                "roots", list(),
                "target_files",
                    list(
                        map(
                            "path",
                            "datadog/2/ASM_FEATURES/asm_features_activation/config",
                            "raw",
                            b64("{\"asm\":{\"enabled\":true}}")),
                        map("path", "foo/ASM_DD/bar/config", "raw", ""),
                        map("path", "foo/ASM/bar/config", "raw", ""),
                        map("path", "foo/ASM_DATA/bar/config", "raw", ""),
                        map("path", "foo/LIVE_DEBUGGING/bar/config", "raw", "")),
                "targets",
                    signAndBase64EncodeTargets(
                        map(
                            "signed",
                            map(
                                "expires",
                                "2022-09-17T12:49:15Z",
                                "spec_version",
                                "1.0.0",
                                "targets",
                                map(
                                    "datadog/2/ASM_FEATURES/asm_features_activation/config",
                                        map(
                                            "custom", map("v", 1),
                                            "hashes",
                                                map(
                                                    "sha256",
                                                    "159658ab85be7207761a4111172b01558394bfc74a1fe1d314f2023f7c656db"),
                                            "length", 24),
                                    "foo/ASM_DD/bar/config", emptyTarget(),
                                    "foo/ASM/bar/config", emptyTarget(),
                                    "foo/ASM_DATA/bar/config", emptyTarget(),
                                    "foo/LIVE_DEBUGGING/bar/config", emptyTarget()),
                                "version",
                                1)))));

    poller.addListener(Product.ASM_DD, deserializer, listeners.get(1));
    poller.addListener(Product.ASM, deserializer, listeners.get(2));
    poller.addListener(Product.ASM_DATA, deserializer, listeners.get(3));
    poller.addListener(Product.LIVE_DEBUGGING, deserializer, listeners.get(0));
    poller.addListener(Product.ASM_FEATURES, deserializer, listeners.get(4));
    start();

    when(deserializer.deserialize(any())).thenReturn(true);
    stubHttp(buildOKResponse(respBody));
    task.run(poller);

    verify(deserializer, times(5)).deserialize(any());
    for (ConfigurationChangesTypedListener<Object> listener : listeners) {
      verify(listener).accept(any(), any(), any());
    }
  }

  @Test
  void reschedulesIfInstructedToDoSo() throws IOException {
    poller.addListener(
        Product.ASM_DD,
        parseDeserializer(),
        (configKey, config, hinter) -> {
          hinter.suggestPollingRate(Duration.ofMillis(124));
          hinter.suggestPollingRate(Duration.ofMillis(123));
          hinter.suggestPollingRate(Duration.ofMillis(1230)); // higher is ignored
        });
    start();

    when(scheduler.scheduleAtFixedRate(any(), eq(poller), eq(123L), eq(123L), eq(MILLISECONDS)))
        .thenAnswer(invocation -> scheduled);
    stubHttp(buildOKResponse(SAMPLE_RESP_BODY));
    task.run(poller);

    verify(scheduler).scheduleAtFixedRate(any(), eq(poller), eq(123L), eq(123L), eq(MILLISECONDS));
    verify(scheduled).cancel();
  }

  @Test
  void setsCachedFilesAndConfigStateOnSecondRequest() throws IOException {
    poller.addListener(Product.ASM_DD, parseDeserializer(), (configKey, config, hinter) -> {});
    start();

    stubHttp(buildOKResponse(SAMPLE_RESP_BODY), buildOKResponse(SAMPLE_RESP_BODY));
    task.run(poller);
    task.run(poller);

    verify(okHttpClient, times(2)).newCall(any());

    Map<String, Object> body = parseBody();
    List<Object> cachedTargetFiles = asList(body.get("cached_target_files"));
    assertEquals(1, cachedTargetFiles.size());
    Map<String, Object> cached = asMap(cachedTargetFiles.get(0));
    assertEquals(
        list(
            map(
                "algorithm",
                "sha256",
                "hash",
                "6302258236e6051216b950583ec7136d946b463c17cbe64384ba5d566324819")),
        cached.get("hashes"));
    assertEquals(919L, asLong(cached.get("length")));
    assertEquals("employee/ASM_DD/1.recommended.json/config", cached.get("path"));

    Map<String, Object> state = clientState(body);
    assertEquals("foobar", state.get("backend_client_state"));
    List<Object> configStates = asList(state.get("config_states"));
    assertEquals(1, configStates.size());
    Map<String, Object> configState = asMap(configStates.get(0));
    assertEquals("1.recommended.json", configState.get("id"));
    assertEquals("ASM_DD", configState.get("product"));
    assertEquals(1L, asLong(configState.get("version")));
  }

  @Test
  void removesCachedFileIfConfigurationIsPulled() throws IOException {
    poller.addListener(Product.ASM_DD, parseDeserializer(), (configKey, config, hinter) -> {});
    start();

    String noConfigs = withClientConfigs(SAMPLE_RESP_BODY, list());
    stubHttp(
        buildOKResponse(SAMPLE_RESP_BODY),
        buildOKResponse(noConfigs),
        buildOKResponse(SAMPLE_RESP_BODY));
    task.run(poller);
    task.run(poller);
    task.run(poller);

    Map<String, Object> body = parseBody();
    assertNull(body.get("cached_target_files"));
  }

  @Test
  void doesNotUpdateTargetsVersionNumberIfThereIsAnError() throws IOException {
    poller.addListener(Product.ASM_DD, parseDeserializer(), (configKey, config, hinter) -> {});
    start();

    Map<String, Object> errored = parseMap(SAMPLE_RESP_BODY);
    errored.put("target_files", list());
    Map<String, Object> targets = decodeTargets(errored.get("targets"));
    asMap(asMap(targets.get("signed")).get("targets"))
        .remove("employee/ASM_DD/1.recommended.json/config");
    asMap(targets.get("signed")).put("version", 42);
    errored.put("targets", signAndBase64EncodeTargets(targets));

    stubHttp(
        buildOKResponse(SAMPLE_RESP_BODY),
        buildOKResponse(toJson(errored)),
        buildOKResponse(SAMPLE_RESP_BODY));
    task.run(poller);
    task.run(poller);
    task.run(poller);

    Map<String, Object> body = parseBody();
    assertNull(body.get("cached_target_files")); // previous hash should be cleared too
    Map<String, Object> state = clientState(body);
    assertEquals("foobar", state.get("backend_client_state"));
    assertTrue(asList(state.get("config_states")).isEmpty());
    assertEquals(Boolean.TRUE, state.get("has_error"));
    assertEquals(
        "Told to apply config for employee/ASM_DD/1.recommended.json/config but no corresponding entry "
            + "exists in targets.targets_signed.targets",
        state.get("error"));
    assertEquals(1L, asLong(state.get("root_version")));
    assertEquals(23337393L, asLong(state.get("targets_version")));
  }

  @Test
  void appliesConfigurationOnlyIfTheHashHasChanged() throws IOException {
    ConfigurationChangesTypedListener<Object> listener = typedListener();
    String configKey = "employee/ASM_DD/1.recommended.json/config";

    poller.addListener(Product.ASM_DD, parseDeserializer(), listener);
    start();

    Map<String, Object> changed = parseMap(SAMPLE_RESP_BODY);
    List<Object> targetFiles = asList(changed.get("target_files"));
    byte[] fileDecoded = Base64.getDecoder().decode((String) asMap(targetFiles.get(0)).get("raw"));
    byte[] newFile = Arrays.copyOf(fileDecoded, fileDecoded.length + 1);
    newFile[fileDecoded.length] = '\n';
    asMap(targetFiles.get(0)).put("raw", Base64.getEncoder().encodeToString(newFile));
    Map<String, Object> targets = decodeTargets(changed.get("targets"));
    Map<String, Object> targetEntry =
        asMap(asMap(asMap(targets.get("signed")).get("targets")).get(configKey));
    asMap(targetEntry.get("hashes")).put("sha256", sha256(newFile).toString(16));
    targetEntry.put("length", asLong(targetEntry.get("length")) + 1);
    changed.put("targets", signAndBase64EncodeTargets(targets));

    stubHttp(
        buildOKResponse(SAMPLE_RESP_BODY),
        buildOKResponse(SAMPLE_RESP_BODY),
        buildOKResponse(toJson(changed)));
    task.run(poller);
    task.run(poller);
    task.run(poller);

    // applied once on the first run (unchanged hash on the second), once again when the hash
    // changed
    verify(listener, times(2)).accept(eq(configKey), notNull(), any());
  }

  @Test
  void configurationCannotBeAppliedWithoutHashes() throws IOException {
    ConfigurationChangesTypedListener<Object> listener = typedListener();
    String configKey = "employee/ASM_DD/1.recommended.json/config";

    poller.addListener(Product.ASM_DD, parseDeserializer(), listener);
    start();

    Map<String, Object> noHashes = parseMap(SAMPLE_RESP_BODY);
    Map<String, Object> targets = decodeTargets(noHashes.get("targets"));
    asMap(asMap(asMap(asMap(targets.get("signed")).get("targets")).get(configKey)).get("hashes"))
        .remove("sha256");
    noHashes.put("targets", signAndBase64EncodeTargets(targets));

    stubHttp(buildOKResponse(toJson(noHashes)), buildOKResponse(SAMPLE_RESP_BODY));
    task.run(poller);
    task.run(poller);

    verify(listener).accept(eq(configKey), any(), any());

    Map<String, Object> state = clientState(parseBody());
    assertEquals(0, asList(state.get("config_states")).size());
    assertEquals("No sha256 hash present for " + configKey, state.get("error"));
    assertEquals(0L, asLong(state.get("targets_version")));
  }

  @Test
  void encodedFileIsNotValidBase64Data() throws IOException {
    ConfigurationChangesTypedListener<Object> listener = typedListener();

    poller.addListener(Product.ASM_DD, parseDeserializer(), listener);
    start();

    Map<String, Object> badBase64 = parseMap(SAMPLE_RESP_BODY);
    List<Object> targetFiles = asList(badBase64.get("target_files"));
    byte[] fileDecoded = Base64.getDecoder().decode((String) asMap(targetFiles.get(0)).get("raw"));
    asMap(targetFiles.get(0)).put("raw", Base64.getEncoder().encodeToString(fileDecoded) + "##");

    stubHttp(buildOKResponse(toJson(badBase64)), buildOKResponse(SAMPLE_RESP_BODY));
    task.run(poller);
    task.run(poller);

    verify(listener).accept(any(), any(), any());

    Map<String, Object> state = clientState(parseBody());
    assertEquals(
        "Could not get file contents from remote config, file employee/ASM_DD/1.recommended.json/config",
        state.get("error"));
  }

  @Test
  void deserializerCanReturnNullToIndicateNoConfigShouldBeApplied() throws IOException {
    ConfigurationChangesTypedListener<Object> listener = typedListener();

    poller.addListener(Product.ASM_DD, content -> null, listener);
    start();

    stubHttp(buildOKResponse(SAMPLE_RESP_BODY));
    task.run(poller);

    verify(listener, never()).accept(any(), any(), any());
  }

  @Test
  void rejectsConfigurationIfTheHashIsWrong() throws IOException {
    ConfigurationChangesTypedListener<Object> listener = typedListener();
    String configKey = "employee/ASM_DD/1.recommended.json/config";

    poller.addListener(Product.ASM_DD, parseDeserializer(), listener);
    start();

    Map<String, Object> wrongHash = parseMap(SAMPLE_RESP_BODY);
    Map<String, Object> targets = decodeTargets(wrongHash.get("targets"));
    asMap(asMap(asMap(asMap(targets.get("signed")).get("targets")).get(configKey)).get("hashes"))
        .put("sha256", "0");
    wrongHash.put("targets", signAndBase64EncodeTargets(targets));

    stubHttp(buildOKResponse(toJson(wrongHash)));
    task.run(poller);

    verify(listener, never()).accept(any(), any(), any());
  }

  @Test
  void acceptsAnEmptyObjectAsAResponseToIndicateNoChanges() throws IOException {
    ConfigurationChangesTypedListener<Object> listener = typedListener();
    ConfigurationDeserializer<Object> deserializer = deserializerMock();

    poller.addListener(Product.ASM_DD, deserializer, listener);
    start();

    stubHttp(buildOKResponse("{}"));
    task.run(poller);

    verifyNoInteractions(deserializer);
    verifyNoInteractions(listener);
  }

  @Test
  void acceptsHttp204AsAResponseToIndicateNoChanges() throws IOException {
    Response resp =
        new Response.Builder()
            .request(REQUEST)
            .protocol(Protocol.HTTP_1_1)
            .message("No Content")
            .body(ResponseBody.create(MediaType.parse("application/json"), ""))
            .code(204)
            .build();
    ConfigurationChangesTypedListener<Object> listener = typedListener();
    ConfigurationDeserializer<Object> deserializer = deserializerMock();

    poller.addListener(Product.ASM_DD, deserializer, listener);
    start();

    stubHttp(resp);
    task.run(poller);

    verifyNoInteractions(deserializer);
    verifyNoInteractions(listener);
  }

  @Test
  void appliesAndRemoveCalledForProductListener() throws IOException {
    ProductListener listener = mock(ProductListener.class);
    String cfgWithoutAsm = cfgWithoutAsm();

    poller.addListener(Product.ASM_DD, listener);
    start();

    stubHttp(
        buildOKResponse(SAMPLE_RESP_BODY),
        buildOKResponse(SAMPLE_RESP_BODY),
        buildOKResponse(cfgWithoutAsm),
        buildOKResponse(cfgWithoutAsm));
    task.run(poller); // accept + commit
    task.run(poller); // no commit, no change
    task.run(poller); // remove + commit
    task.run(poller); // no commit, no change

    verify(listener, times(1)).accept(any(), notNull(), any());
    verify(listener, times(1)).remove(any(), any());
    verify(listener, times(2)).commit(any());
  }

  @Test
  void unappliesConfigurationsItHasStoppedSeeing() throws IOException {
    ConfigurationChangesTypedListener<Object> listener = typedListener();
    String cfgWithoutAsm = cfgWithoutAsm();

    poller.addListener(Product.ASM_DD, parseDeserializer(), listener);
    start();

    stubHttp(
        buildOKResponse(SAMPLE_RESP_BODY),
        buildOKResponse(cfgWithoutAsm),
        buildOKResponse(cfgWithoutAsm));
    task.run(poller); // accept with non-null config
    task.run(poller); // unapply (accept with null config)
    task.run(poller); // nothing more

    verify(listener, times(1)).accept(any(), notNull(), any());
    verify(listener, times(1)).accept(any(), isNull(), any());
  }

  @Test
  void supportMultipleConfigurations() throws IOException {
    ConfigurationChangesTypedListener<Object> listener = typedListener();
    String recommended = "employee/ASM_DD/1.recommended.json/config";
    String suggested = "employee/ASM_DD/2.suggested.json/config";
    String multiConfigs = withClientConfigs(SAMPLE_RESP_BODY, list(recommended, suggested));
    String noConfigs = withClientConfigs(SAMPLE_RESP_BODY, list());

    poller.addListener(Product.ASM_DD, parseDeserializer(), listener);
    start();

    stubHttp(
        buildOKResponse(SAMPLE_RESP_BODY), // apply first configuration
        buildOKResponse(multiConfigs), // apply second configuration
        buildOKResponse(SAMPLE_RESP_BODY), // remove second configuration
        buildOKResponse(noConfigs)); // remove all configurations
    task.run(poller);
    task.run(poller);
    task.run(poller);
    task.run(poller);

    verify(listener, times(1)).accept(eq(recommended), notNull(), any());
    verify(listener, times(1)).accept(eq(suggested), notNull(), any());
    verify(listener, times(1)).accept(eq(suggested), isNull(), any());
    verify(listener, times(1)).accept(eq(recommended), isNull(), any());
  }

  @Test
  void exceptionApplyingOneConfigShouldNotPreventOthersFromBeingApplied() throws IOException {
    String newConfigId = "1ba66cc9-146a-3479-9e66-2b63fd580f48";
    String newConfigKey = "datadog/2/LIVE_DEBUGGING/" + newConfigId + "/config";

    poller.addListener(
        Product.ASM_DD,
        parseDeserializer(),
        (configKey, config, hinter) -> {
          throw new RuntimeException("throw here");
        });
    poller.addListener(
        Product.LIVE_DEBUGGING, parseDeserializer(), (configKey, config, hinter) -> {});
    start();

    Map<String, Object> withExtra = parseMap(SAMPLE_RESP_BODY);
    asList(withExtra.get("client_configs")).add(newConfigKey);
    Map<String, Object> targets = decodeTargets(withExtra.get("targets"));
    asMap(asMap(targets.get("signed")).get("targets"))
        .put(
            newConfigKey,
            map(
                "custom", map("v", 3),
                "hashes",
                    map(
                        "sha256",
                        "7a38bf81f383f69433ad6e900d35b3e2385593f76a7b7ab5d4355b8ba41ee24b"),
                "length", "{\"foo\":\"bar\"}".length()));
    asList(withExtra.get("target_files"))
        .add(map("path", newConfigKey, "raw", b64("{\"foo\":\"bar\"}")));
    withExtra.put("targets", signAndBase64EncodeTargets(targets));

    stubHttp(buildOKResponse(toJson(withExtra)), buildOKResponse(SAMPLE_RESP_BODY));
    task.run(poller);
    task.run(poller);

    Map<String, Object> body = parseBody();
    List<Object> configStates = asList(clientState(body).get("config_states"));
    assertEquals(2, configStates.size());
    Map<String, Object> liveDebuggingConfig = findConfigState(configStates, "LIVE_DEBUGGING");
    Map<String, Object> asmConfig = findConfigState(configStates, "ASM_DD");
    assertEquals(newConfigId, liveDebuggingConfig.get("id"));
    assertEquals("LIVE_DEBUGGING", liveDebuggingConfig.get("product"));
    assertEquals(3L, asLong(liveDebuggingConfig.get("version")));
    assertNull(liveDebuggingConfig.get("apply_error"));
    assertEquals("1.recommended.json", asmConfig.get("id"));
    assertEquals("ASM_DD", asmConfig.get("product"));
    assertEquals(1L, asLong(asmConfig.get("version")));
    assertEquals(APPLY_STATE_ERROR, asInt(asmConfig.get("apply_state")));
    assertEquals("throw here", asmConfig.get("apply_error"));
  }

  static Stream<Arguments> badResponsesArguments() {
    return Stream.of(
        arguments(
            "404 with body",
            new Response.Builder()
                .request(REQUEST)
                .protocol(Protocol.HTTP_1_1)
                .message("Not Found")
                .code(404)
                .body(ResponseBody.create(MediaType.get("text/plain"), "not found!"))
                .build()),
        arguments(
            "404 without body",
            new Response.Builder()
                .request(REQUEST)
                .protocol(Protocol.HTTP_1_1)
                .message("Not Found")
                .code(404)
                .build()),
        arguments(
            "success, no body",
            new Response.Builder()
                .request(REQUEST)
                .protocol(Protocol.HTTP_1_1)
                .message("Created")
                .code(201)
                .build()),
        arguments(
            "not json",
            new Response.Builder()
                .request(REQUEST)
                .protocol(Protocol.HTTP_1_1)
                .message("OK")
                .body(ResponseBody.create(MediaType.get("text/plain"), SAMPLE_RESP_BODY))
                .code(200)
                .build()));
  }

  // autoCloseArguments disabled: a body-less okhttp Response throws on close()
  @ParameterizedTest(name = "bad responses: {0}", autoCloseArguments = false)
  @MethodSource("badResponsesArguments")
  void badResponses(String scenario, Response resp) throws IOException {
    ConfigurationChangesTypedListener<Object> listener = typedListener();

    poller.addListener(Product.ASM_DD, deserializerThrowing("should not be called"), listener);
    start();

    stubHttp(resp);
    task.run(poller);

    // a single request is made and no configuration is applied
    verify(okHttpClient, times(1)).newCall(any());
    verifyNoInteractions(listener);
  }

  static Stream<Arguments> bodyDoesNotSatisfyFormatArguments() {
    return Stream.of(
        arguments("targets not a string", "{\"targets\": []}"),
        arguments("targets not base64", "{\"targets\": \"ZZZZ=\"}"),
        arguments(
            "signed not an object", "{\"targets\": \"" + b64("{\"signed\": \"string\"}") + "\"}"));
  }

  @ParameterizedTest(name = "body does not satisfy format: {0}")
  @MethodSource("bodyDoesNotSatisfyFormatArguments")
  void bodyDoesNotSatisfyFormat(String scenario, String bodyStr) throws IOException {
    ConfigurationDeserializer<Object> deserializer = deserializerMock();
    ConfigurationChangesTypedListener<Object> listener = typedListener();

    poller.addListener(Product.ASM_DD, deserializer, listener);
    start();

    stubHttp(buildOKResponse(bodyStr));
    task.run(poller);

    verifyNoInteractions(deserializer);
    verifyNoInteractions(listener);
  }

  static Stream<Arguments> reportableErrorsArguments() {
    // not a valid key
    Map<String, Object> notValidKey = parseMap(SAMPLE_RESP_BODY);
    asList(notValidKey.get("client_configs")).set(0, "foobar");

    // no file for the given key
    Map<String, Object> noFile = parseMap(SAMPLE_RESP_BODY);
    noFile.put("target_files", list());

    // two reportable errors
    Map<String, Object> twoErrors = parseMap(SAMPLE_RESP_BODY);
    twoErrors.put("client_configs", list("foobar", "employee/ASM_DD/1.recommended.json/config"));
    twoErrors.put("target_files", list());

    // in target_files, but not targets.signed.targets
    Map<String, Object> notInTargets = parseMap(SAMPLE_RESP_BODY);
    Map<String, Object> targetsNotInTargets = decodeTargets(notInTargets.get("targets"));
    asMap(targetsNotInTargets.get("signed")).put("targets", map());
    notInTargets.put("targets", signAndBase64EncodeTargets(targetsNotInTargets));

    // told to apply config that is not subscribed
    Map<String, Object> notSubscribed = parseMap(SAMPLE_RESP_BODY);
    notSubscribed.put(
        "client_configs",
        list("datadog/2/LIVE_DEBUGGING/1ba66cc9-146a-3479-9e66-2b63fd580f48/config"));

    // invalid signature
    Map<String, Object> invalidSignature = parseMap(SAMPLE_RESP_BODY);
    Map<String, Object> invalidSigTargets = decodeTargets(invalidSignature.get("targets"));
    asMap(asList(invalidSigTargets.get("signatures")).get(0))
        .put(
            "sig",
            "59a6478aba87d171261e6995faaa8e36c95c3e75436c4e82f11ac625220e13b703ce9b912ee0731415121b5a47aa2abdb398a60656b7701b15e606c6327c880e");
    invalidSignature.put(
        "targets", Base64.getEncoder().encodeToString(toJson(invalidSigTargets).getBytes(UTF_8)));

    // structurally invalid signature
    Map<String, Object> structurallyInvalid = parseMap(SAMPLE_RESP_BODY);
    Map<String, Object> structurallyInvalidTargets =
        decodeTargets(structurallyInvalid.get("targets"));
    asMap(asList(structurallyInvalidTargets.get("signatures")).get(0)).put("sig", repeat("a", 128));
    structurallyInvalid.put(
        "targets",
        Base64.getEncoder().encodeToString(toJson(structurallyInvalidTargets).getBytes(UTF_8)));

    return Stream.of(
        arguments("not a valid key", toJson(notValidKey), "Not a valid config key: foobar"),
        arguments(
            "no file for key",
            toJson(noFile),
            "No content for employee/ASM_DD/1.recommended.json/config"),
        arguments(
            "two reportable errors",
            toJson(twoErrors),
            "Failed to apply configuration due to 2 errors:\n (1) Not a valid config key: foobar\n"
                + " (2) No content for employee/ASM_DD/1.recommended.json/config\n"),
        arguments(
            "in target_files but not signed",
            toJson(notInTargets),
            "Path employee/ASM_DD/1.recommended.json/config is in target_files, but not in targets.signed"),
        arguments(
            "config not subscribed",
            toJson(notSubscribed),
            "Told to handle config key datadog/2/LIVE_DEBUGGING/1ba66cc9-146a-3479-9e66-2b63fd580f48/config,"
                + " but the product LIVE_DEBUGGING is not being handled"),
        arguments(
            "invalid signature",
            toJson(invalidSignature),
            "Signature verification failed for targets.signed. Key id: TEST_KEY_ID"),
        arguments(
            "structurally invalid signature",
            toJson(structurallyInvalid),
            "Error reading signature or canonicalizing targets.signed: Invalid scalar representation"));
  }

  @ParameterizedTest(name = "reportable errors: {0}")
  @MethodSource("reportableErrorsArguments")
  void reportableErrors(String scenario, String bodyStr, String errorMsg) throws IOException {
    poller.addListener(
        Product.ASM_DD,
        deserializerThrowing("should not be called"),
        (configKey, config, hinter) -> {});
    start();

    stubHttp(buildOKResponse(bodyStr), buildOKResponse(SAMPLE_RESP_BODY));
    task.run(poller);
    task.run(poller);

    Map<String, Object> state = clientState(parseBody());
    assertTrue(asList(state.get("config_states")).isEmpty());
    assertEquals(Boolean.TRUE, state.get("has_error"));
    assertEquals(errorMsg, state.get("error"));
  }

  @Test
  void reportsErrorDuringDeserialization() throws IOException {
    poller.addListener(
        Product.ASM_DD,
        content -> {
          throw new RuntimeException("my deserializer error");
        },
        (configKey, config, hinter) -> {});
    start();

    stubHttp(buildOKResponse(SAMPLE_RESP_BODY), buildOKResponse(SAMPLE_RESP_BODY));
    task.run(poller);
    task.run(poller);

    Map<String, Object> configState =
        asMap(asList(clientState(parseBody()).get("config_states")).get(0));
    assertEquals(APPLY_STATE_ERROR, asInt(configState.get("apply_state")));
    assertEquals("my deserializer error", configState.get("apply_error"));
  }

  @Test
  void reportsErrorApplyingConfiguration() throws IOException {
    poller.addListener(
        Product.ASM_DD,
        content -> true,
        (configKey, config, hinter) -> {
          throw new RuntimeException("error applying config");
        });
    start();

    stubHttp(buildOKResponse(SAMPLE_RESP_BODY), buildOKResponse(SAMPLE_RESP_BODY));
    task.run(poller);
    task.run(poller);

    Map<String, Object> configState =
        asMap(asList(clientState(parseBody()).get("config_states")).get(0));
    assertEquals(APPLY_STATE_ERROR, asInt(configState.get("apply_state")));
    assertEquals("error applying config", configState.get("apply_error"));
  }

  @Test
  void theMaxSizeIsExceeded() throws IOException {
    ConfigurationDeserializer<Object> deserializer = deserializerMock();

    poller.addListener(
        Product.ASM_DD, deserializer, (configKey, config, hinter) -> fail("should not be called"));
    start();

    Map<String, Object> oversized = parseMap(SAMPLE_RESP_BODY);
    asList(oversized.get("target_files"))
        .add(
            map(
                "path",
                "foo/bar",
                "raw",
                repeat("a", (int) Config.get().getRemoteConfigMaxPayloadSizeBytes())));

    stubHttp(buildOKResponse(toJson(oversized)));
    task.run(poller);

    verifyNoInteractions(deserializer);
  }

  @Test
  void canListenForChangesInALocalFile() throws IOException {
    File file = Files.createTempFile(null, ".json").toFile();
    AtomicReference<Object> savedConf = new AtomicReference<>();

    poller.addFileListener(file, parseDeserializer(), (path, conf, hinter) -> savedConf.set(conf));
    start();
    Files.write(file.toPath(), "{\"foo\":\"bar\"}".getBytes(UTF_8));

    task.run(poller);
    assertEquals("bar", asMap(savedConf.get()).get("foo"));

    file.delete();
    Files.write(file.toPath(), "{\"foo\":\"xpto\"}".getBytes(UTF_8));
    task.run(poller);
    assertEquals("xpto", asMap(savedConf.get()).get("foo"));

    file.delete();
  }

  @Test
  void distributesFeatures() throws IOException {
    ConfigurationChangesTypedListener<Object> listener = typedListener();

    poller.addListener(Product.ASM_FEATURES, parseDeserializer(), listener);
    start();

    stubHttp(buildOKResponse(FEATURES_RESP_BODY));
    task.run(poller);

    verify(listener)
        .accept(
            any(),
            cfgMatches(cfg -> Boolean.TRUE.equals(asMap(cfg.get("asm")).get("enabled"))),
            any());
  }

  @Test
  void distributesFeaturesUponSubscribing() throws IOException {
    ConfigurationChangesTypedListener<Object> listener = typedListener();

    poller.addListener(
        Product.ASM_FEATURES,
        deserializerThrowing("should not be called"),
        (configKey, config, hinter) -> {
          throw new RuntimeException("should not be called");
        });
    start();

    poller.removeListeners(Product.ASM_FEATURES);

    poller.addListener(Product.ASM_FEATURES, parseDeserializer(), listener);
    stubHttp(buildOKResponse(FEATURES_RESP_BODY));
    task.run(poller);

    verify(listener)
        .accept(
            any(),
            cfgMatches(cfg -> Boolean.TRUE.equals(asMap(cfg.get("asm")).get("enabled"))),
            any());
  }

  @Test
  void distributeConfigAcrossMultipleListenersForSameSubscriber() throws IOException {
    ConfigurationChangesTypedListener<Object> listener1 = typedListener();
    ConfigurationChangesTypedListener<Object> listener2 = typedListener();

    poller.addListener(Product.ASM_FEATURES, parseDeserializer(), listener1);
    poller.addListener(Product.ASM_FEATURES, parseDeserializer(), listener2);
    start();

    stubHttp(buildOKResponse(FEATURES_RESP_BODY));
    task.run(poller);

    verify(listener1)
        .accept(
            any(),
            cfgMatches(cfg -> Boolean.TRUE.equals(asMap(cfg.get("asm")).get("enabled"))),
            any());
    verify(listener2)
        .accept(
            any(),
            cfgMatches(
                cfg ->
                    Double.valueOf(0.1)
                        .equals(asMap(cfg.get("api_security")).get("request_sample_rate"))),
            any());
  }

  @Test
  void errorApplyingFeatures() throws IOException {
    AtomicBoolean called = new AtomicBoolean(false);

    poller.addListener(
        Product.ASM_FEATURES,
        content -> true,
        (configKey, config, hinter) -> {
          called.set(true);
          throw new RuntimeException("throws");
        });
    start();

    stubHttp(buildOKResponse(FEATURES_RESP_BODY));
    task.run(poller);

    assertTrue(called.get());
    // error does not escape
  }

  @Test
  void removingFeatureListeners() throws IOException {
    ConfigurationChangesTypedListener<Object> listener = typedListener();

    poller.addListener(
        Product._UNKNOWN,
        deserializerThrowing("should not be called"),
        (configKey, config, hinter) -> {
          throw new RuntimeException("should not be called");
        });
    poller.addListener(Product.ASM_FEATURES, content -> null, listener);
    poller.removeListeners(Product.ASM_FEATURES);
    start();

    stubHttp(buildOKResponse(FEATURES_RESP_BODY));
    task.run(poller);

    verify(listener, never()).accept(any(), any(), any()); // listener is not called

    poller.removeListeners(Product._UNKNOWN);
    task.run(poller);

    verify(okHttpClient, times(1)).newCall(any()); // not even a request is made the second time
  }

  @Test
  void checkSettingOfCapabilitiesNegativeTest() throws IOException {
    ConfigurationChangesTypedListener<Object> listener = typedListener();

    poller.addListener(Product._UNKNOWN, content -> true, listener);
    start();

    stubHttp(buildOKResponse(FEATURES_RESP_BODY));
    task.run(poller);

    List<Object> capabilities = asList(asMap(parseBody().get("client")).get("capabilities"));
    assertEquals(0L, asLong(capabilities.get(0)));
  }

  static Stream<Arguments> checkSettingOfCapabilitiesPositiveTestArguments() {
    return Stream.of(
        arguments("zero", 0L, new byte[] {0}),
        arguments("fourteen", 14L, new byte[] {14}),
        arguments("1 << 8", 1L << 8, new byte[] {1, 0}),
        arguments("1 << 9", 1L << 9, new byte[] {2, 0}),
        arguments(
            "long min plus one",
            -9223372036854775807L,
            new byte[] {(byte) 128, 0, 0, 0, 0, 0, 0, 1}));
  }

  @ParameterizedTest(name = "check setting of capabilities positive test: {0}")
  @MethodSource("checkSettingOfCapabilitiesPositiveTestArguments")
  void checkSettingOfCapabilitiesPositiveTest(String scenario, long capabilities, byte[] encoded)
      throws IOException {
    poller.addListener(Product._UNKNOWN, content -> true, (configKey, config, hinter) -> {});
    poller.addCapabilities(capabilities);
    start();

    stubHttp(buildOKResponse(FEATURES_RESP_BODY));
    task.run(poller);

    List<Object> capabilitiesList = asList(asMap(parseBody().get("client")).get("capabilities"));
    byte[] actual = new byte[capabilitiesList.size()];
    for (int i = 0; i < actual.length; i++) {
      actual[i] = (byte) asLong(capabilitiesList.get(i));
    }
    assertArrayEquals(encoded, actual);
  }

  // ----- Helper methods -----

  private void start() {
    when(scheduler.scheduleAtFixedRate(
            any(), eq(poller), eq(0L), eq((long) DEFAULT_POLL_PERIOD), eq(MILLISECONDS)))
        .thenAnswer(
            invocation -> {
              task = invocation.getArgument(0);
              return scheduled;
            });
    poller.start();
    assertNotNull(task);
  }

  private void stubHttp(Response... responses) throws IOException {
    when(okHttpClient.newCall(any(Request.class)))
        .thenAnswer(
            invocation -> {
              request = invocation.getArgument(0);
              return call;
            });
    OngoingStubbing<Response> stubbing = when(call.execute());
    for (Response response : responses) {
      stubbing = stubbing.thenReturn(response);
    }
  }

  private String bodyJson() throws IOException {
    Buffer buffer = new Buffer();
    request.body().writeTo(buffer);
    return new String(buffer.readByteArray(), UTF_8);
  }

  private Map<String, Object> parseBody() throws IOException {
    return parseMap(bodyJson());
  }

  private static Map<String, Object> clientState(Map<String, Object> body) {
    return asMap(asMap(body.get("client")).get("state"));
  }

  private static Map<String, Object> findConfigState(List<Object> configStates, String product) {
    for (Object configState : configStates) {
      Map<String, Object> map = asMap(configState);
      if (product.equals(map.get("product"))) {
        return map;
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static ConfigurationChangesTypedListener<Object> typedListener() {
    return mock(ConfigurationChangesTypedListener.class);
  }

  @SuppressWarnings("unchecked")
  private static ConfigurationDeserializer<Object> deserializerMock() {
    return mock(ConfigurationDeserializer.class);
  }

  private static ConfigurationDeserializer<Object> parseDeserializer() {
    return content -> parse(new String(content, UTF_8));
  }

  private static ConfigurationDeserializer<Object> deserializerThrowing(String message) {
    return content -> {
      throw new RuntimeException(message);
    };
  }

  private static Object cfgMatches(Predicate<Map<String, Object>> predicate) {
    return argThat(arg -> arg != null && predicate.test(asMap(arg)));
  }

  private static String cfgWithoutAsm() {
    Map<String, Object> map = parseMap(SAMPLE_RESP_BODY);
    map.put("client_configs", list());
    Map<String, Object> targets = decodeTargets(map.get("targets"));
    asMap(
            asMap(asMap(targets.get("signed")).get("targets"))
                .get("employee/ASM_DD/1.recommended.json/config"))
        .put(
            "hashes",
            map("sha256", "aec070645fe53ee3b3763059376134f058cc337247c978add178b6ccdfb0019f"));
    map.put("targets", signAndBase64EncodeTargets(targets));
    return toJson(map);
  }

  private static String withClientConfigs(String body, List<Object> clientConfigs) {
    Map<String, Object> map = parseMap(body);
    map.put("client_configs", clientConfigs);
    return toJson(map);
  }

  private static Map<String, Object> emptyTarget() {
    return map(
        "custom",
        map("v", 1),
        "hashes",
        map("sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
        "length",
        0);
  }

  private static String signAndBase64EncodeTargets(Map<String, Object> targets) {
    Map<String, Object> targetsSigned = asMap(targets.get("signed"));
    if (targetsSigned != null) {
      byte[] canonicalTargetsSigned = JsonCanonicalizer.canonicalize(targetsSigned);
      Ed25519Signature signature = PRIVATE_KEY.expand().sign(canonicalTargetsSigned, PUBLIC_KEY);
      String sigBase16 = new BigInteger(1, signature.toByteArray()).toString(16);
      targets.put("signatures", list(map("keyid", KEY_ID, "sig", sigBase16)));
    }
    return Base64.getEncoder().encodeToString(toJson(targets).getBytes(UTF_8));
  }

  private static Map<String, Object> decodeTargets(Object base64Targets) {
    byte[] decoded = Base64.getDecoder().decode((String) base64Targets);
    return parseMap(new String(decoded, UTF_8));
  }

  private static BigInteger sha256(byte[] bytes) {
    try {
      return new BigInteger(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static String b64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(UTF_8));
  }

  private static String repeat(String value, int count) {
    StringBuilder builder = new StringBuilder(value.length() * count);
    for (int i = 0; i < count; i++) {
      builder.append(value);
    }
    return builder.toString();
  }

  private static long asLong(Object value) {
    return ((Number) value).longValue();
  }

  private static int asInt(Object value) {
    return ((Number) value).intValue();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asList(Object value) {
    return (List<Object>) value;
  }

  private static Map<String, Object> map(Object... keyValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put((String) keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  private static List<Object> list(Object... items) {
    return new ArrayList<>(Arrays.asList(items));
  }

  private static Object parse(String json) {
    try {
      return MOSHI.adapter(Object.class).fromJson(json);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static Map<String, Object> parseMap(String json) {
    return asMap(parse(json));
  }

  private static String toJson(Object value) {
    return MOSHI.adapter(Object.class).toJson(value);
  }

  private static final String SAMPLE_APPSEC_CONFIG =
      "\n"
          + "{\n"
          + "    \"version\": \"2.2\",\n"
          + "    \"metadata\": {\n"
          + "        \"rules_version\": \"1.3.1\"\n"
          + "    },\n"
          + "    \"rules\": [\n"
          + "        {\n"
          + "            \"id\": \"crs-913-110\",\n"
          + "            \"name\": \"Acunetix\",\n"
          + "            \"tags\": {\n"
          + "                \"type\": \"security_scanner\",\n"
          + "                \"crs_id\": \"913110\",\n"
          + "                \"category\": \"attack_attempt\"\n"
          + "            },\n"
          + "            \"conditions\": [\n"
          + "                {\n"
          + "                    \"parameters\": {\n"
          + "                        \"inputs\": [\n"
          + "                            {\n"
          + "                                \"address\": \"server.request.headers.no_cookies\"\n"
          + "                            }\n"
          + "                        ],\n"
          + "                        \"list\": [\n"
          + "                            \"acunetix-product\"\n"
          + "                        ]\n"
          + "                    },\n"
          + "                    \"operator\": \"phrase_match\"\n"
          + "                }\n"
          + "            ],\n"
          + "            \"transformers\": [\n"
          + "                \"lowercase\"\n"
          + "            ]\n"
          + "        }\n"
          + "    ]\n"
          + "}\n";

  private static final String SAMPLE_TARGETS =
      "\n"
          + "{\n"
          + "   \"signatures\" : [\n"
          + "      {\n"
          + "         \"keyid\" : \"5c4ece41241a1bb513f6e3e5df74ab7d5183dfffbd71bfd43127920d880569fd\",\n"
          + "         \"sig\" : \"766871ed1acc60ef35f9f24262682283a55e79334f5154486176033b67568aed82fe8139a1f78689b96473537f0a2e55c8365d50bff345ea9ac350d57b90390d\"\n"
          + "      }\n"
          + "   ],\n"
          + "   \"signed\" : {\n"
          + "      \"_type\" : \"targets\",\n"
          + "      \"custom\" : {\n"
          + "         \"opaque_backend_state\" : \"foobar\"\n"
          + "      },\n"
          + "      \"expires\" : \"2022-09-17T12:49:15Z\",\n"
          + "      \"spec_version\" : \"1.0.0\",\n"
          + "      \"targets\" : {\n"
          + "         \"employee/ASM_DD/1.recommended.json/config\" : {\n"
          + "            \"custom\" : {\n"
          + "               \"v\" : 1\n"
          + "            },\n"
          + "            \"hashes\" : {\n"
          + "               \"sha256\" : \"6302258236e6051216b950583ec7136d946b463c17cbe64384ba5d566324819\"\n"
          + "            },\n"
          + "            \"length\" : 919\n"
          + "         },\n"
          + "         \"employee/ASM_DD/2.suggested.json/config\" : {\n"
          + "            \"custom\" : {\n"
          + "               \"v\" : 1\n"
          + "            },\n"
          + "            \"hashes\" : {\n"
          + "               \"sha256\" : \"6302258236e6051216b950583ec7136d946b463c17cbe64384ba5d566324819\"\n"
          + "            },\n"
          + "            \"length\" : 919\n"
          + "         },\n"
          + "         \"employee/CWS_DD/2.default.policy/config\" : {\n"
          + "            \"custom\" : {\n"
          + "               \"v\" : 2\n"
          + "            },\n"
          + "            \"hashes\" : {\n"
          + "               \"sha256\" : \"2f075fcaa9bdfc96bfc30d5a18711fbebf59d2cb3f3b5258d41ebdc1b1a54569\"\n"
          + "            },\n"
          + "            \"length\" : 34805\n"
          + "         }\n"
          + "      },\n"
          + "      \"version\" : 23337393\n"
          + "   }\n"
          + "}\n";

  private static final String SAMPLE_RESP_BODY =
      "{\n"
          + "   \"client_configs\" : [\n"
          + "      \"employee/ASM_DD/1.recommended.json/config\"\n"
          + "   ],\n"
          + "   \"roots\" : [],\n"
          + "   \"target_files\" : [\n"
          + "      {\n"
          + "         \"path\" : \"employee/ASM_DD/1.recommended.json/config\",\n"
          + "         \"raw\" : \""
          + b64(SAMPLE_APPSEC_CONFIG)
          + "\"\n"
          + "      },\n"
          + "      {\n"
          + "         \"path\" : \"employee/ASM_DD/2.suggested.json/config\",\n"
          + "         \"raw\" : \""
          + b64(SAMPLE_APPSEC_CONFIG)
          + "\"\n"
          + "      }\n"
          + "   ],\n"
          + "   \"targets\" : \""
          + signAndBase64EncodeTargets(SAMPLE_TARGETS)
          + "\"\n"
          + "}\n";

  private static final String FEATURES_RESP_BODY =
      toJson(
          map(
              "client_configs", list("datadog/2/ASM_FEATURES/asm_features_activation/config"),
              "roots", list(),
              "target_files",
                  list(
                      map(
                          "path",
                          "datadog/2/ASM_FEATURES/asm_features_activation/config",
                          "raw",
                          b64(
                              "{\"asm\":{\"enabled\":true},\"api_security\":{\"request_sample_rate\":0.1}}"))),
              "targets",
                  signAndBase64EncodeTargets(
                      map(
                          "signed",
                          map(
                              "expires",
                              "2022-09-17T12:49:15Z",
                              "spec_version",
                              "1.0.0",
                              "targets",
                              map(
                                  "datadog/2/ASM_FEATURES/asm_features_activation/config",
                                  map(
                                      "custom", map("v", 1),
                                      "hashes",
                                          map(
                                              "sha256",
                                              "b01cb68f140fbfb7a2bb0ce39a473aa59c33664aca0c871cf07b8f4e09e3e360"),
                                      "length", 67)),
                              "version",
                              23337393)))));

  private static String signAndBase64EncodeTargets(String targetsJson) {
    return signAndBase64EncodeTargets(parseMap(targetsJson));
  }
}
