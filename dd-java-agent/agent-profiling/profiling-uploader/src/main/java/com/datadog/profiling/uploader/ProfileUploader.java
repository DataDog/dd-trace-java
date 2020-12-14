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

import static datadog.trace.util.AgentThreadFactory.AgentThread.PROFILER_HTTP_DISPATCHER;

import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.RecordingType;
import com.datadog.profiling.uploader.util.PidHelper;
import com.datadog.profiling.uploader.util.StreamUtils;
import datadog.common.container.ContainerInfo;
import datadog.trace.api.Config;
import datadog.trace.api.IOLogger;
import datadog.trace.util.AgentThreadFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.Credentials;
import okhttp3.Dispatcher;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** The class for uploading profiles to the backend. */
@Slf4j
public final class ProfileUploader {

  private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

  static final String FORMAT_PARAM = "format";
  static final String TYPE_PARAM = "type";
  static final String RUNTIME_PARAM = "runtime";

  static final String PROFILE_START_PARAM = "recording-start";
  static final String PROFILE_END_PARAM = "recording-end";

  // TODO: We should rename parameter to just `data`
  static final String DATA_PARAM = "chunk-data";

  static final String TAGS_PARAM = "tags[]";

  static final String HEADER_DD_API_KEY = "DD-API-KEY";
  static final String HEADER_DD_CONTAINER_ID = "Datadog-Container-ID";

  static final String JAVA_LANG = "java";
  static final String DATADOG_META_LANG = "Datadog-Meta-Lang";

  static final int MAX_RUNNING_REQUESTS = 10;
  static final int MAX_ENQUEUED_REQUESTS = 20;

  static final String PROFILE_FORMAT = "jfr";
  static final String PROFILE_TYPE_PREFIX = "jfr-";
  static final String PROFILE_RUNTIME = "jvm";

  static final int TERMINATION_TIMEOUT = 5;

  private static final Headers DATA_HEADERS =
      Headers.of(
          "Content-Disposition", "form-data; name=\"" + DATA_PARAM + "\"; filename=\"profile\"");

  private static final class ResponseCallback implements Callback {

    private final IOLogger ioLogger;

    public ResponseCallback(final IOLogger ioLogger) {
      this.ioLogger = ioLogger;
    }

    @Override
    public void onFailure(final Call call, final IOException e) {
      if (isEmptyReplyFromServer(e)) {
        ioLogger.error(
            "Received empty reply from " + call.request().url() + " after uploading profile");
      } else {
        ioLogger.error("Failed to upload profile to " + call.request().url(), e);
      }
    }

    @Override
    public void onResponse(final Call call, final Response response) {
      if (response.isSuccessful()) {
        ioLogger.success("Upload done");
      } else {
        final String apiKey = call.request().header(HEADER_DD_API_KEY);
        if (response.code() == 404 && apiKey == null) {
          // if no API key and not found error we assume we're sending to the agent
          ioLogger.error(
              "Datadog Agent is not accepting profiles. Agent-based profiling deployments require Datadog Agent >= 7.20");
        }

        ioLogger.error("Failed to upload profile", getLoggerResponse(response));
      }
      response.close();
    }

    private static IOLogger.Response getLoggerResponse(okhttp3.Response response) {
      if (response != null) {
        try {
          return new IOLogger.Response(
              response.code(), response.message(), response.body().string().trim());
        } catch (NullPointerException | IOException ignored) {
        }
      }
      return null;
    }

    private static boolean isEmptyReplyFromServer(IOException e) {
      // The server in datadog-agent triggers 'unexpected end of stream' caused by EOFException.
      // The MockWebServer in tests triggers an InterruptedIOException with SocketPolicy
      // NO_RESPONSE. This is because in tests we can't cleanly terminate the connection on the
      // server side without resetting.
      return (e instanceof InterruptedIOException)
          || (e.getCause() != null && e.getCause() instanceof java.io.EOFException);
    }
  }

  static final int SEED_EXPECTED_REQUEST_SIZE = 2 * 1024 * 1024; // 2MB;
  static final int REQUEST_SIZE_HISTORY_SIZE = 10;
  static final double REQUEST_SIZE_COEFFICIENT = 1.2;

  private final ExecutorService okHttpExecutorService;
  private final OkHttpClient client;
  private final Callback responseCallback;
  private final String apiKey;
  private final String url;
  private final String containerId;
  private final List<String> tags;
  private final Compression compression;
  private final Deque<Integer> requestSizeHistory;

  public ProfileUploader(final Config config) {
    this(config, new IOLogger(log), ContainerInfo.get().getContainerId());
  }

  /**
   * Note that this method is only visible for testing and should not be used from outside this
   * class.
   */
  ProfileUploader(final Config config, final IOLogger ioLogger, final String containerId) {
    url = config.getFinalProfilingUrl();
    apiKey = config.getApiKey();
    responseCallback = new ResponseCallback(ioLogger);
    this.containerId = containerId;

    log.debug("Started ProfileUploader with target url {}", url);
    /*
    FIXME: currently `Config` class cannot get access to some pieces of information we need here:
    * PID (see PidHelper for details),
    * Profiler version
    Since Config returns unmodifiable map we have to do copy here.
    Ideally we should improve this logic and avoid copy, but performance impact is very limited
    since we are doing this once on startup only.
    */
    final Map<String, String> tagsMap = new HashMap<>(config.getMergedProfilingTags());
    tagsMap.put(VersionInfo.PROFILER_VERSION_TAG, VersionInfo.VERSION);
    // PID can be null if we cannot find it out from the system
    if (PidHelper.PID != null) {
      tagsMap.put(PidHelper.PID_TAG, PidHelper.PID.toString());
    }
    tags = tagsToList(tagsMap);

    // This is the same thing OkHttp Dispatcher is doing except thread naming and daemonization
    okHttpExecutorService =
        new ThreadPoolExecutor(
            0,
            Integer.MAX_VALUE,
            60,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new AgentThreadFactory(PROFILER_HTTP_DISPATCHER));
    // Reusing connections causes non daemon threads to be created which causes agent to prevent app
    // from exiting. See https://github.com/square/okhttp/issues/4029 for some details.
    final ConnectionPool connectionPool =
        new ConnectionPool(MAX_RUNNING_REQUESTS, 1, TimeUnit.SECONDS);

    // Use same timeout everywhere for simplicity
    final Duration requestTimeout = Duration.ofSeconds(config.getProfilingUploadTimeout());
    final OkHttpClient.Builder clientBuilder =
        new OkHttpClient.Builder()
            .connectTimeout(requestTimeout)
            .writeTimeout(requestTimeout)
            .readTimeout(requestTimeout)
            .callTimeout(requestTimeout)
            .dispatcher(new Dispatcher(okHttpExecutorService))
            .connectionPool(connectionPool);

    if (config.getFinalProfilingUrl().startsWith("http://")) {
      // force clear text when using http to avoid failures for JVMs without TLS
      // see: https://github.com/DataDog/dd-trace-java/pull/1582
      clientBuilder.connectionSpecs(Collections.singletonList(ConnectionSpec.CLEARTEXT));
    }

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

    compression = getCompression(CompressionType.of(config.getProfilingUploadCompression()));

    requestSizeHistory = new ArrayDeque<>(REQUEST_SIZE_HISTORY_SIZE);
    requestSizeHistory.add(SEED_EXPECTED_REQUEST_SIZE);
  }

  public void upload(final RecordingType type, final RecordingData data) {
    try {
      if (canEnqueueMoreRequests()) {
        makeUploadRequest(type, data);
      } else {
        log.warn("Cannot upload profile data: too many enqueued requests!");
      }
    } catch (final IllegalStateException | IOException e) {
      log.warn("Problem uploading profile!", e);
    } finally {
      try {
        data.getStream().close();
      } catch (final IllegalStateException | IOException e) {
        log.warn("Problem closing profile stream", e);
      }
      data.release();
    }
  }

  public void shutdown() {
    okHttpExecutorService.shutdownNow();
    try {
      okHttpExecutorService.awaitTermination(TERMINATION_TIMEOUT, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      // Note: this should only happen in main thread right before exiting, so eating up interrupted
      // state should be fine.
      log.warn("Wait for executor shutdown interrupted");
    }

    client.connectionPool().evictAll();
  }

  /**
   * Note that this method is only visible for testing and should not be used from outside this
   * class.
   */
  OkHttpClient getClient() {
    return client;
  }

  @FunctionalInterface
  private interface Compression {

    RequestBody compress(InputStream is, int expectedSize) throws IOException;
  }

  private Compression getCompression(final CompressionType type) {
    final StreamUtils.BytesConsumer<RequestBody> consumer =
        (bytes, offset, length) -> RequestBody.create(OCTET_STREAM, bytes, offset, length);
    final Compression compression;
    // currently only gzip and off are supported
    // this needs to be updated once more compression types are added
    switch (type) {
      case GZIP:
        {
          compression = (is, expectedSize) -> StreamUtils.gzipStream(is, expectedSize, consumer);
          break;
        }
      case OFF:
        {
          compression = (is, expectedSize) -> StreamUtils.readStream(is, expectedSize, consumer);
          break;
        }
      case ON:
      case LZ4:
      default:
        {
          compression = (is, expectedSize) -> StreamUtils.lz4Stream(is, expectedSize, consumer);
          break;
        }
    }
    return compression;
  }

  private void makeUploadRequest(final RecordingType type, final RecordingData data)
      throws IOException {
    final int expectedRequestSize = getExpectedRequestSize();
    // TODO: it would be really nice to avoid copy here, but:
    // * if JFR doesn't write file to disk we seem to not be able to get size of the recording
    // without reading whole stream
    // * OkHTTP doesn't provide direct way to send uploads from streams - and workarounds would
    // require stream that allows 'repeatable reads' because we may need to resend that data.
    final RequestBody body = compression.compress(data.getStream(), expectedRequestSize);
    if (log.isDebugEnabled()) {
      log.debug(
          "Uploading profile {} [{}] (Size={}/{} bytes)",
          data.getName(),
          type,
          body.contentLength(),
          expectedRequestSize);
    }

    // The body data is stored in byte array so we naturally get size limit that will fit into int
    updateUploadSizesHistory((int) body.contentLength());

    final MultipartBody.Builder bodyBuilder =
        new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(FORMAT_PARAM, PROFILE_FORMAT)
            .addFormDataPart(TYPE_PARAM, PROFILE_TYPE_PREFIX + type.getName())
            .addFormDataPart(RUNTIME_PARAM, PROFILE_RUNTIME)
            // Note that toString is well defined for instants - ISO-8601
            .addFormDataPart(PROFILE_START_PARAM, data.getStart().toString())
            .addFormDataPart(PROFILE_END_PARAM, data.getEnd().toString());
    for (final String tag : tags) {
      bodyBuilder.addFormDataPart(TAGS_PARAM, tag);
    }
    bodyBuilder.addPart(DATA_HEADERS, body);
    final RequestBody requestBody = bodyBuilder.build();

    final Request.Builder requestBuilder =
        new Request.Builder()
            .url(url)
            // Note: this header is used to disable tracing of profiling requests
            .addHeader(DATADOG_META_LANG, JAVA_LANG)
            .post(requestBody);
    if (apiKey != null) {
      requestBuilder.addHeader(HEADER_DD_API_KEY, apiKey);
    }
    if (containerId != null) {
      requestBuilder.addHeader(HEADER_DD_CONTAINER_ID, containerId);
    }
    client.newCall(requestBuilder.build()).enqueue(responseCallback);
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
    return tags.entrySet().stream()
        .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
        .map(e -> e.getKey() + ":" + e.getValue())
        .collect(Collectors.toList());
  }
}
