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
import com.datadog.profiling.uploader.util.PidHelper;
import com.datadog.profiling.uploader.util.StreamUtils;
import com.datadog.profiling.util.ProfilingThreadFactory;
import com.google.common.annotations.VisibleForTesting;
import datadog.common.exec.CommonTaskExecutor;
import datadog.trace.api.Config;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
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

  static final String JAVA_LANG = "java";
  static final String DATADOG_META_LANG = "Datadog-Meta-Lang";

  static final double MAX_BACKOFF_TIME_MS = 10_000; // milliseconds
  static final int INFINITE_OKHTTP_REQUESTS = 1000;
  static final double baseBackoffMs = Duration.ofMillis(50).toMillis();
  final int MaxRunningRequests;
  final int MaxRetryUpload;

  static final String PROFILE_FORMAT = "jfr";
  static final String PROFILE_TYPE_PREFIX = "jfr-";
  static final String PROFILE_RUNTIME = "jvm";

  static final int TERMINATION_TIMEOUT = 5;

  private static final Headers DATA_HEADERS =
      Headers.of(
          "Content-Disposition", "form-data; name=\"" + DATA_PARAM + "\"; filename=\"profile\"");

  static final int SEED_EXPECTED_REQUEST_SIZE = 2 * 1024 * 1024; // 2MB;
  static final int REQUEST_SIZE_HISTORY_SIZE = 10;
  static final double REQUEST_SIZE_COEFFICIENT = 1.2;

  private final ExecutorService okHttpExecutorService;
  private final OkHttpClient client;
  private final String apiKey;
  private final String url;
  private final List<String> tags;
  private final Compression compression;
  private final Deque<Integer> requestSizeHistory;

  @VisibleForTesting
  final LinkedList<RunningRequest> runningRequests;

  public ProfileUploader(final Config config) {
    url = config.getFinalProfilingUrl();
    apiKey = config.getApiKey();

    /*
    FIXME: currently `Config` class cannot get access to some pieces of information we need here:
    * PID (see PidHelper for details),
    * Profiler version
    Since Config returns unmodifiable map we have to do copy here.
    Ideally we should improve this logic and avoid copy, but performace impact is very limtied
    since we are doing this once on startup only.
    */
    final Map<String, String> tagsMap = new HashMap<>(config.getMergedProfilingTags());
    tagsMap.put(VersionInfo.PROFILER_VERSION_TAG, VersionInfo.VERSION);
    // PID can be null if we cannot find it out from the system
    if (PidHelper.PID != null) {
      tagsMap.put(PidHelper.PID_TAG, PidHelper.PID.toString());
    }
    tags = tagsToList(tagsMap);

    MaxRetryUpload = config.getProfilingMaxRetryUpload();
    MaxRunningRequests = 1 + MaxRetryUpload;
    runningRequests = new LinkedList<>();

    // This is the same thing OkHttp Dispatcher is doing except thread naming and deamonization
    okHttpExecutorService =
        new ThreadPoolExecutor(
            0,
            Integer.MAX_VALUE,
            60,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new ProfilingThreadFactory("dd-profiler-http-dispatcher"));
    // Reusing connections causes non daemon threads to be created which causes agent to prevent app
    // from exiting. See https://github.com/square/okhttp/issues/4029 for some details.
    final ConnectionPool connectionPool =
        new ConnectionPool(INFINITE_OKHTTP_REQUESTS, 1, TimeUnit.SECONDS);

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
    client.dispatcher().setMaxRequests(INFINITE_OKHTTP_REQUESTS);
    // We are mainly talking to the same(ish) host so we need to raise this limit
    client.dispatcher().setMaxRequestsPerHost(INFINITE_OKHTTP_REQUESTS);

    compression = getCompression(CompressionType.of(config.getProfilingUploadCompression()));

    requestSizeHistory = new ArrayDeque<>(REQUEST_SIZE_HISTORY_SIZE);
    requestSizeHistory.add(SEED_EXPECTED_REQUEST_SIZE);
  }

  public void upload(final RecordingType type, final RecordingData data) {
    try {
      ensureNewRequestCanBeEnqueued();
      makeUploadRequest(type, data);
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

    final Request request =
        new Request.Builder()
            .url(url)
            .addHeader(HEADER_DD_API_KEY, apiKey)
            // Note: this header is used to disable tracing of profiling requests
            .addHeader(DATADOG_META_LANG, JAVA_LANG)
            .post(requestBody)
            .build();

    Call call = client.newCall(request);
    try {
      // The caller has made space for this request by calling ensureNewRequestCanBeEnqueued()
      addRunningRequest(call);
      call.enqueue(new RetryCallback());
    } catch (Exception e) {
      removeRunningRequest(call);
    }
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

  private List<String> tagsToList(final Map<String, String> tags) {
    return tags.entrySet()
        .stream()
        .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
        .map(e -> e.getKey() + ":" + e.getValue())
        .collect(Collectors.toList());
  }

  private class RetryCallback implements Callback {
    private int retryCount = 0;

    private void tryRetryRequest(Call call) {
      RunningRequest retryRequest;
      synchronized (runningRequests) {
        int requestIndex = runningRequests.indexOf(new RunningRequest(call));
        retryRequest = runningRequests.get(requestIndex);
      }

      if (retryCount++ >= MaxRetryUpload) {
        removeRunningRequest(retryRequest.call);
        log.error("Failed to upload recording after {} retries", retryCount - 1);
        return;
      }

      // "EqualJitter" formula from
      // https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
      int temp = (int) Math.min(MAX_BACKOFF_TIME_MS, Math.pow(baseBackoffMs, retryCount));
      int backoffMs = temp / 2 + ThreadLocalRandom.current().nextInt(0, temp / 2);

      Instant start = Instant.now();
      CommonTaskExecutor.INSTANCE.schedule(
        request -> {
          if (!request.isCanceled()) {
            log.warn("Retry upload {} time(s) after waiting for {} ms", retryCount, Duration.between(start, Instant.now()).toMillis());

            Call newCall = client.newCall(request.call.request());
            request.setCall(newCall);
            newCall.enqueue(RetryCallback.this);
          } else {
            // The request has already been removed from runningRequest queue
            log.warn("Cancel retry upload after {} retry(ies)", retryCount - 1);
          }
        },
          retryRequest,
          backoffMs,
          TimeUnit.MILLISECONDS,
          "Profile upload retry");
    }

    @Override
    public void onFailure(final Call call, final IOException e) {
      log.error("Failed to upload recording", e);
      tryRetryRequest(call);
    }

    @Override
    public void onResponse(final Call call, final Response response) {
      if (response.isSuccessful()) {
        log.debug("Upload done");
        removeRunningRequest(call);
      } else if (response.code() == 408 || response.code() == 500) {
        // HTTP status 408: Request timeout
        log.error("Failed to upload recording (HTTP status:{})", response.code());
        tryRetryRequest(call);
      } else {
        log.error(
            "Failed to upload recording: unexpected response code {} {}",
            response.message(),
            response.code());
        removeRunningRequest(call);
      }
      response.close();
    }
  }

  private void ensureNewRequestCanBeEnqueued() {
    synchronized (runningRequests) {
      while (runningRequests.size() >= MaxRunningRequests) {
        RunningRequest request = runningRequests.pop();
        log.warn("Cancel data upload: too many enqueued requests!");
        request.cancel();
      }
    }
  }

  private boolean removeRunningRequest(Call call) {
    synchronized (runningRequests) {
      return runningRequests.remove(new RunningRequest(call));
    }
  }

  private void addRunningRequest(Call call) {
    synchronized (runningRequests) {
      runningRequests.add(new RunningRequest(call));
    }
  }

  class RunningRequest {
    private volatile Call call;
    private volatile boolean cancel;

    public RunningRequest(Call call) {
      this.call = call;
      this.cancel = false;
    }

    public boolean isCanceled() {
      return cancel;
    }

    public void cancel() {
      this.cancel = true;
      this.call.cancel();
    }

    public void setCall(Call call) {
      this.call = call;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      RunningRequest that = (RunningRequest) o;
      return call.equals(that.call);
    }

    @Override
    public int hashCode() {
      return Objects.hash(call);
    }

    @Override
    public String toString() {
      return "RunningRequest{" +
        "call=" + call +
        ", cancel=" + cancel +
        '}';
    }
  }
}
