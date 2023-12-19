package datadog.trace.core.flare;

import static datadog.trace.api.flare.TracerFlare.addText;
import static datadog.trace.util.AgentThreadFactory.AgentThread.TRACER_FLARE;

import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.flare.TracerFlare;
import datadog.trace.core.DDTraceCoreInfo;
import datadog.trace.util.AgentTaskScheduler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipOutputStream;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TracerFlareService {
  private static final Logger log = LoggerFactory.getLogger(TracerFlareService.class);

  private static final String FLARE_ENDPOINT = "tracer_flare/v1";

  private static final MediaType OCTET_STREAM = MediaType.get("application/octet-stream");

  private final AgentTaskScheduler scheduler = new AgentTaskScheduler(TRACER_FLARE);

  private final Config config;
  private final DynamicConfig dynamicConfig;
  private final OkHttpClient okHttpClient;
  private final HttpUrl flareUrl;

  private AtomicBoolean preparingTracerFlare = new AtomicBoolean();

  TracerFlareService(
      Config config, DynamicConfig dynamicConfig, OkHttpClient okHttpClient, HttpUrl agentUrl) {
    this.config = config;
    this.dynamicConfig = dynamicConfig;
    this.okHttpClient = okHttpClient;
    this.flareUrl = agentUrl.newBuilder().addPathSegments(FLARE_ENDPOINT).build();
  }

  public void prepareTracerFlare(String logLevel) {
    if (preparingTracerFlare.compareAndSet(false, true)) {
      log.debug("Preparing tracer flare, logLevel={}", logLevel);
    }
  }

  public void sendFlare(String caseId, String email, String hostname) {
    log.debug("Sending tracer flare");
    scheduler.execute(() -> doSendFlare(caseId, email, hostname));
  }

  public void cancelTracerFlare() {
    if (preparingTracerFlare.compareAndSet(true, false)) {
      log.debug("Canceling tracer flare");
    }
  }

  private void doSendFlare(String caseId, String email, String hostname) {
    try {
      RequestBody report = RequestBody.create(OCTET_STREAM, buildFlareZip());

      RequestBody form =
          new MultipartBody.Builder()
              .setType(MultipartBody.FORM)
              .addFormDataPart("source", "tracer_java")
              .addFormDataPart("case_id", caseId)
              .addFormDataPart("email", email)
              .addFormDataPart("hostname", hostname)
              .addFormDataPart("flare_file", "java-flare.zip", report)
              .build();

      Request flareRequest =
          OkHttpUtils.prepareRequest(flareUrl, Collections.emptyMap()).post(form).build();

      try (Response response = okHttpClient.newCall(flareRequest).execute()) {
        if (response.code() == 404) {
          log.debug("Tracer flare endpoint is disabled, ignoring request");
        } else if (!response.isSuccessful()) {
          log.warn("Tracer flare failed with: {} {}", response.code(), response.message());
        } else {
          log.debug("Tracer flare sent successfully");
        }
      }

    } catch (IOException e) {
      log.warn("Tracer flare failed with exception: {}", e.toString());
    } finally {
      preparingTracerFlare.set(false);
    }
  }

  private byte[] buildFlareZip() throws IOException {
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes)) {

      addPrelude(zip);
      TracerFlare.buildFlare(zip);
      zip.finish();

      return bytes.toByteArray();
    }
  }

  private void addPrelude(ZipOutputStream zip) throws IOException {
    addText(zip, "version.txt", DDTraceCoreInfo.VERSION);
    addText(zip, "classpath.txt", System.getProperty("java.class.path"));
    addText(zip, "initial_config.txt", config.toString());
    addText(zip, "dynamic_config.txt", dynamicConfig.toString());
  }
}
