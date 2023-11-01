package datadog.trace.core.flare;

import static datadog.trace.util.AgentThreadFactory.AgentThread.TRACER_FLARE;

import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.DynamicConfig;
import datadog.trace.util.AgentTaskScheduler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
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

  private final DynamicConfig dynamicConfig;
  private final OkHttpClient okHttpClient;
  private final HttpUrl flareUrl;

  private AtomicBoolean preparingTracerFlare = new AtomicBoolean();

  TracerFlareService(DynamicConfig dynamicConfig, OkHttpClient okHttpClient, HttpUrl agentUrl) {
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
    RequestBody report = RequestBody.create(OCTET_STREAM, placeholderZip());

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
    } catch (IOException e) {
      log.warn("Tracer flare failed with exception: {}", e.toString());
    } finally {
      preparingTracerFlare.set(false);
    }
  }

  private static byte[] placeholderZip() {
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("tracer.txt"));
      zip.closeEntry();
      zip.finish();
      return bytes.toByteArray();
    } catch (IOException e) {
      return new byte[0]; // never called
    }
  }
}
