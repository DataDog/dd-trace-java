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
import com.datadog.profiling.uploader.util.StreamUtils;
import datadog.trace.api.Config;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.Headers;
import okhttp3.HttpUrl;
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

  static final String[] URL_PATH_SEGMENTS = {"v1", "input"};
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

  static final int SEED_EXPECTED_REQUEST_SIZE = 2 * 1024 * 1024; // 2MB;
  // Should this be guessed somehow from how often we run periodic profiles?
  static final int REQUEST_SIZE_HISTORY_SIZE = 10;
  static final double REQUEST_SIZE_COEFFICIENT = 1.2;

  private final OkHttpClient client;
  private final String apiKey;
  private final HttpUrl url;
  private final List<String> tags;
  private final Compression compression;
  private final Deque<Integer> requestSizeHistory;

  public RecordingUploader(final Config config) {
    url = createProfilingUrl(config);
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

    compression = getCompression(config.getProfilingUploadCompressionLevel());

    requestSizeHistory = new ArrayDeque<>(REQUEST_SIZE_HISTORY_SIZE);
    requestSizeHistory.add(SEED_EXPECTED_REQUEST_SIZE);
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

  @FunctionalInterface
  private interface Compression {
    RequestBody compress(InputStream is, int expectedSize) throws IOException;
  }

  private Compression getCompression(final String level) {
    final CompressionLevel cLevel = CompressionLevel.of(level);
    log.debug("Uploader compression level = {}", cLevel);
    final StreamUtils.BytesConsumer<RequestBody> consumer =
        (bytes, offset, length) -> RequestBody.create(OCTET_STREAM, bytes, offset, length);
    final Compression compression;
    // currently only gzip and off are supported
    // this needs to be updated once more compression levels are added
    switch (cLevel) {
      case ON:
        {
          compression = (is, expectedSize) -> StreamUtils.gzipStream(is, expectedSize, consumer);
          break;
        }
      case OFF:
        {
          compression = (is, expectedSize) -> StreamUtils.readStream(is, expectedSize, consumer);
          break;
        }
      default:
        {
          log.warn("Unrecognizable compression level: {}. Defaulting to 'on'.", cLevel);
          compression = (is, expectedSize) -> StreamUtils.gzipStream(is, expectedSize, consumer);
        }
    }
    return compression;
  }

  private void makeUploadRequest(final RecordingType type, final RecordingData data)
      throws IOException {
    // TODO: it would be really nice to avoid copy here, but:
    // * if JFR doesn't write file to disk we seem to not be able to get size of the recording
    // without reading whole stream
    // * OkHTTP doesn't provide direct way to send uploads from streams - and workarounds would
    // require stream that allows 'repeatable reads' because we may need to resend that data.
    final RequestBody body = compression.compress(data.getStream(), getExpectedRequestSize());
    log.debug(
        "Uploading recording {} [{}] (Size={}/{} bytes)",
        data.getName(),
        type,
        body.contentLength(),
        getExpectedRequestSize());

    // The body data is stored in byte array so we naturally get size limit that will fit into int
    updateUploadSizesHistory((int) body.contentLength());

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
    bodyBuilder.addPart(DATA_HEADERS, body);
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

  private int getExpectedRequestSize() {
    synchronized (requestSizeHistory) {
      // We have added seed value, so history cannot be empty
      int size = 0;
      for (final int s : requestSizeHistory) {
        if (s > size) {
          size = s;
        }
      }
      return (int) (size * REQUEST_SIZE_COEFFICIENT);
    }
  }

  private void updateUploadSizesHistory(final int newSize) {
    synchronized (requestSizeHistory) {
      while (requestSizeHistory.size() >= REQUEST_SIZE_HISTORY_SIZE) {
        requestSizeHistory.removeLast();
      }
      requestSizeHistory.offerFirst(newSize);
    }
  }

  private boolean canEnqueueMoreRequests() {
    return client.dispatcher().queuedCallsCount() < MAX_ENQUEUED_REQUESTS;
  }

  private List<String> tagsToList(final Map<String, String> tags) {
    return tags.entrySet()
        .stream()
        .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
        .map(e -> e.getKey() + ":" + e.getValue())
        .collect(Collectors.toList());
  }

  private static HttpUrl createProfilingUrl(final Config config) {
    HttpUrl.Builder builder = HttpUrl.get(config.getProfilingUrl()).newBuilder();
    for (final String segment : URL_PATH_SEGMENTS) {
      builder = builder.addPathSegment(segment);
    }
    // Adding API key to url has implications that we would rather avoid for now
    // builder = builder.addPathSegment(config.getProfilingApiKey())
    return builder.build();
  }
}
