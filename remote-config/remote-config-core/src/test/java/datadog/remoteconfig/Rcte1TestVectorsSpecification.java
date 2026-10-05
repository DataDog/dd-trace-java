package datadog.remoteconfig;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.squareup.moshi.Moshi;
import datadog.trace.api.Config;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import datadog.trace.util.AgentTaskScheduler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

@WithConfig(
    key = "rc.targets.key.id",
    value = "ed7672c9a24abda78872ee32ee71c7cb1d5235e8db4ecbf1ca28b9c50eb75d9e")
@WithConfig(
    key = "rc.targets.key",
    value = "7d3102e39abe71044d207550bda239c71380d013ec5a115f79f51622630054e6")
@WithConfig(key = "remote_config.integrity_check.enabled", value = "true")
class Rcte1TestVectorsSpecification extends DDJavaSpecification {
  private static final int DEFAULT_POLL_PERIOD = 5000;

  private static final HttpUrl URL = HttpUrl.get("https://example.com/v0.7/config");
  private static final Request REQUEST = new Request.Builder().url("https://example.com").build();

  private final Moshi moshi = new Moshi.Builder().build();

  private final OkHttpClient okHttpClient = mock(OkHttpClient.class);
  private final AgentTaskScheduler scheduler = mock(AgentTaskScheduler.class);

  @SuppressWarnings("unchecked")
  private final AgentTaskScheduler.Scheduled<ConfigurationPoller> scheduled =
      mock(AgentTaskScheduler.Scheduled.class);

  private final Call call = mock(Call.class);

  private ConfigurationPoller poller;
  private AgentTaskScheduler.Task<ConfigurationPoller> task;
  private Request request;

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
    Supplier<String> urlSupplier = URL::toString;
    poller =
        new DefaultConfigurationPoller(
            Config.get(), "0.0.0", "containerid", "entityid", urlSupplier, okHttpClient, scheduler);
  }

  private static String getFileContents(String baseFileName) throws IOException {
    return new String(
        Files.readAllBytes(Paths.get("src/test/resources/rcte1/" + baseFileName + ".json")),
        StandardCharsets.UTF_8);
  }

  private static String bodyToString(RequestBody body) throws IOException {
    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    return buffer.readUtf8();
  }

  private void stubScheduling() {
    when(scheduler.scheduleAtFixedRate(
            any(), eq(poller), eq(0L), eq((long) DEFAULT_POLL_PERIOD), eq(TimeUnit.MILLISECONDS)))
        .thenAnswer(
            invocation -> {
              task = invocation.getArgument(0);
              return scheduled;
            });
  }

  @Test
  void validFile() throws IOException {
    AtomicReference<Object> savedConfig = new AtomicReference<>();
    ConfigurationDeserializer<Object> deserializer =
        content ->
            moshi.adapter(Object.class).fromJson(new String(content, StandardCharsets.UTF_8));
    ConfigurationChangesTypedListener<Object> listener =
        (configKey, config, hinter) -> savedConfig.set(config);
    poller.addListener(Product.ASM_DD, deserializer, listener);

    stubScheduling();
    poller.start();
    assertNotNull(task);

    when(okHttpClient.newCall(any(Request.class)))
        .thenAnswer(
            invocation -> {
              request = invocation.getArgument(0);
              return call;
            });
    when(call.execute()).thenReturn(buildOKResponse(getFileContents("validOneFile")));

    task.run(poller);

    assertNotNull(savedConfig.get());
  }

  @TableTest({
    "scenario            | baseFileName                    | message                                                                                     ",
    "invalid signing key | targetsSignedWithInvalidKey     | 'Missing signature for key ed7672c9a24abda78872ee32ee71c7cb1d5235e8db4ecbf1ca28b9c50eb75d9e'",
    "invalid signature   | tufTargetsInvalidSignature      | 'Signature verification failed for targets.signed'                                          ",
    "invalid file hash   | tufTargetsInvalidTargetFileHash | 'does not have the expected sha256 hash'                                                    ",
    "missing target file | tufTargetsMissingTargetFile     | 'is in target_files, but not in targets.signed'                                             "
  })
  void invalidFile(String baseFileName, String message) throws IOException {
    ConfigurationDeserializer<Object> deserializer =
        content -> {
          fail("should never be called");
          return null;
        };
    ConfigurationChangesTypedListener<Object> listener = (configKey, config, hinter) -> {};
    poller.addListener(Product.ASM_DD, deserializer, listener);

    stubScheduling();
    poller.start();
    assertNotNull(task);

    when(okHttpClient.newCall(any(Request.class)))
        .thenAnswer(
            invocation -> {
              request = invocation.getArgument(0);
              return call;
            });
    when(call.execute())
        .thenReturn(buildOKResponse(getFileContents(baseFileName)))
        .thenReturn(buildOKResponse("validOneFile"));

    task.run(poller);
    task.run(poller);

    String bodyJson = bodyToString(request.body());
    assertThatJson(bodyJson).node("client.state.has_error").isEqualTo(true);
    assertThatJson(bodyJson).node("client.state.error").asString().contains(message);
  }
}
