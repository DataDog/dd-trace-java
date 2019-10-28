/*
 * Copyright 2019 Datadog
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datadog.profiling.uploader;

import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.RecordingType;
import com.google.common.io.ByteStreams;
import datadog.trace.api.Config;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** The class for uploading recordings to the backend. */
@Slf4j
public final class RecordingUploader {
  private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

  static final String RECORDING_NAME_PARAM = "recording-name";
  static final String FORMAT_PARAM = "format";
  static final String TYPE_PARAM = "type";
  static final String RUNTIME_PARAM = "runtime";

  static final String RECORDING_START_PARAM = "recording-start";
  static final String RECORDING_END_PARAM = "recording-end";

  // TODO: We should rename parameter to just `data`
  static final String DATA_PARAM = "chunk-data";

  static final String TAGS_PARAM = "tags[]";

  static final int MAX_RUNNING_REQUESTS = 10;
  static final int MAX_ENQUEUED_REQUESTS = 20;

  static final String RECORDING_FORMAT = "jfr";
  static final String RECORDING_TYPE_PREFIX = "jfr-";
  static final String RECORDING_RUNTIME = "jvm";

  private static final Headers DATA_HEADERS =
      Headers.of(
          "Content-Disposition", "form-data; name=\"" + DATA_PARAM + "\"; filename=\"recording\"");

  private static final Callback RESPONSE_CALLBACK =
      new Callback() {
        @Override
        public void onFailure(final Call call, final IOException e) {
          log.error("Failed to upload recording", e);
        }

        @Override
        public void onResponse(final Call call, final Response response) {
          if (response.isSuccessful()) {
            log.debug("Upload done");
          } else {
            log.error(
                "Failed to upload recording: unexpected response code {} {}",
                response.message(),
                response.code());
          }
          response.close();
        }
      };

  private final OkHttpClient client;
  private final String apiKey;
  private final String url;
  private final List<String> tags;

  public RecordingUploader(final Config config) {
    url = config.getProfilingUrl();
    apiKey = config.getProfilingApiKey();
    tags = tagsToList(config.getMergedProfilingTags());

    final Duration ioOperationTimeout =
        Duration.ofSeconds(config.getProfilingUploadRequestIOOperationTimeout());
    final OkHttpClient.Builder clientBuilder =
        new OkHttpClient.Builder()
            .connectTimeout(ioOperationTimeout)
            .writeTimeout(ioOperationTimeout)
            .readTimeout(ioOperationTimeout)
            .callTimeout(Duration.ofSeconds(config.getProfilingUploadRequestTimeout()));

    if (config.getProfilingProxyHost() != null) {
      final Proxy proxy =
          new Proxy(
              Proxy.Type.HTTP,
              new InetSocketAddress(
                  config.getProfilingProxyHost(), config.getProfilingProxyPort()));
      clientBuilder.proxy(proxy);
      if (config.getProfilingProxyUsername() != null) {
        // Empty password by default
        final String password =
            config.getProfilingProxyPassword() == null ? "" : config.getProfilingProxyPassword();
        clientBuilder.proxyAuthenticator(
            (route, response) -> {
              final String credential =
                  Credentials.basic(config.getProfilingProxyUsername(), password);
              return response
                  .request()
                  .newBuilder()
                  .header("Proxy-Authorization", credential)
                  .build();
            });
      }
    }

    client = clientBuilder.build();
    client.dispatcher().setMaxRequests(MAX_RUNNING_REQUESTS);
    // We are mainly talking to the same(ish) host so we need to raise this limit
    client.dispatcher().setMaxRequestsPerHost(MAX_RUNNING_REQUESTS);
  }

  public void upload(final RecordingType type, final RecordingData data) {
    try {
      if (canEnqueueMoreRequests()) {
        makeUploadRequest(type, data);
      } else {
        log.error("Cannot upload data: too many enqueued requests!");
      }
    } catch (final IllegalStateException | IOException e) {
      log.error("Problem uploading recording!", e);
    } finally {
      try {
        data.getStream().close();
      } catch (final IllegalStateException | IOException e) {
        log.error("Problem closing recording stream", e);
      }
      data.release();
    }
  }

  public void shutdown() {
    client.dispatcher().executorService().shutdown();
  }

  private void makeUploadRequest(final RecordingType type, final RecordingData data)
      throws IOException {
    // TODO: we some point we would want to compress this. But this may be already compressed: see
    // com.datadog.profiling.uploader.util.IOToolkit
    final byte[] bytes = ByteStreams.toByteArray(data.getStream());
    log.debug("Uploading recording {} [{}] (Size={} bytes)", data.getName(), bytes.length);

    final MultipartBody.Builder bodyBuilder =
        new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(RECORDING_NAME_PARAM, data.getName())
            .addFormDataPart(FORMAT_PARAM, RECORDING_FORMAT)
            .addFormDataPart(TYPE_PARAM, RECORDING_TYPE_PREFIX + type.getName())
            .addFormDataPart(RUNTIME_PARAM, RECORDING_RUNTIME)
            // Note that toString is well defined for instants - ISO-8601
            .addFormDataPart(RECORDING_START_PARAM, data.getStart().toString())
            .addFormDataPart(RECORDING_END_PARAM, data.getEnd().toString());
    for (final String tag : tags) {
      bodyBuilder.addFormDataPart(TAGS_PARAM, tag);
    }
    bodyBuilder.addPart(DATA_HEADERS, RequestBody.create(OCTET_STREAM, bytes));
    final RequestBody requestBody = bodyBuilder.build();

    final Request request =
        new Request.Builder()
            .url(url)
            .addHeader("Authorization", Credentials.basic(apiKey, ""))
            // Note: this header is also used to disable tracing of profiling requests
            .addHeader(VersionInfo.DATADOG_META_LANG, VersionInfo.JAVA_LANG)
            .addHeader(VersionInfo.DATADOG_META_LANG_VERSION, VersionInfo.JAVA_VERSION)
            .addHeader(VersionInfo.DATADOG_META_LANG_INTERPRETER, VersionInfo.JAVA_VM_NAME)
            .addHeader(VersionInfo.DATADOG_META_LANG_INTERPRETER_VENDOR, VersionInfo.JAVA_VM_VENDOR)
            .addHeader(VersionInfo.DATADOG_META_TRACER_VERSION, VersionInfo.VERSION)
            .post(requestBody)
            .build();

    client.newCall(request).enqueue(RESPONSE_CALLBACK);
  }

  private boolean canEnqueueMoreRequests() {
    return client.dispatcher().queuedCallsCount() < MAX_ENQUEUED_REQUESTS;
  }

  private List<String> tagsToList(final Map<String, String> tags) {
    return tags.entrySet().stream()
        .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
        .map(e -> e.getKey() + ":" + e.getValue())
        .collect(Collectors.toList());
  }
}
