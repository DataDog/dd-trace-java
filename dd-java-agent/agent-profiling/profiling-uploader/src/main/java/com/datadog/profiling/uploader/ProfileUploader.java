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
import datadog.common.container.ContainerInfo;
import datadog.trace.api.Config;
import datadog.trace.api.IOLogger;
import datadog.trace.util.AgentProxySelector;
import datadog.trace.util.AgentThreadFactory;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.Credentials;
import okhttp3.Dispatcher;
import okhttp3.Headers;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The class for uploading profiles to the backend. */
public final class ProfileUploader {

  private static final Logger log = LoggerFactory.getLogger(ProfileUploader.class);

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
      // Note: this whole callback never touches body and would be perfectly happy even if server
      // never sends it.
      response.close();
    }

    private static IOLogger.Response getLoggerResponse(final okhttp3.Response response) {
      if (response != null) {
        try {
          return new IOLogger.Response(
              response.code(), response.message(), response.body().string().trim());
        } catch (final NullPointerException | IOException ignored) {
        }
      }
      return null;
    }

    private static boolean isEmptyReplyFromServer(final IOException e) {
      // The server in datadog-agent triggers 'unexpected end of stream' caused by EOFException.
      // The MockWebServer in tests triggers an InterruptedIOException with SocketPolicy
      // NO_RESPONSE. This is because in tests we can't cleanly terminate the connection on the
      // server side without resetting.
      return (e instanceof InterruptedIOException)
          || (e.getCause() != null && e.getCause() instanceof java.io.EOFException);
    }
  }

  private final ExecutorService okHttpExecutorService;
  private final OkHttpClient client;
  private final Callback responseCallback;
  private final boolean agentless;
  private final String apiKey;
  private final String url;
  private final String containerId;
  private final int terminationTimeout;
  private final List<String> tags;
  private final CompressionType compressionType;

  public ProfileUploader(final Config config) throws IOException {
    this(config, new IOLogger(log), ContainerInfo.get().getContainerId(), TERMINATION_TIMEOUT);
  }

  /**
   * Note that this method is only visible for testing and should not be used from outside this
   * class.
   */
  ProfileUploader(
      final Config config,
      final IOLogger ioLogger,
      final String containerId,
      final int terminationTimeout)
      throws IOException {
    url = config.getFinalProfilingUrl();
    apiKey = config.getApiKey();
    agentless = config.isProfilingAgentless();
    responseCallback = new ResponseCallback(ioLogger);
    this.containerId = containerId;
    this.terminationTimeout = terminationTimeout;

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
            .retryOnConnectionFailure(true)
            .connectTimeout(requestTimeout)
            .writeTimeout(requestTimeout)
            .readTimeout(requestTimeout)
            .callTimeout(requestTimeout)
            .proxySelector(AgentProxySelector.INSTANCE)
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

    compressionType = CompressionType.of(config.getProfilingUploadCompression());
  }

  /**
   * Enqueue an upload request. Do not receive any notification when the upload has been completed.
   *
   * @param type {@link RecordingType recording type}
   * @param data {@link RecordingData recording data}
   */
  public void upload(final RecordingType type, final RecordingData data) {
    upload(type, data, () -> {});
  }

  /**
   * Enqueue an upload request and run the provided hook when that request is completed
   * (successfully or failing).
   *
   * @param type {@link RecordingType recording type}
   * @param data {@link RecordingData recording data}
   * @param onCompletion call-back to execute once the request is completed (successfully or
   *     failing)
   */
  public void upload(
      final RecordingType type, final RecordingData data, @Nonnull Runnable onCompletion) {
    if (canEnqueueMoreRequests()) {
      makeUploadRequest(
          type,
          data,
          () -> {
            data.release();
            onCompletion.run();
          });
      return;
    } else {
      log.warn("Cannot upload profile data: too many enqueued requests!");
    }
    // the request was not made; release the recording data
    data.release();
  }

  public void shutdown() {
    okHttpExecutorService.shutdownNow();
    try {
      okHttpExecutorService.awaitTermination(terminationTimeout, TimeUnit.SECONDS);
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

  private void makeUploadRequest(
      @Nonnull final RecordingType type,
      @Nonnull final RecordingData data,
      @Nonnull Runnable onCompletion) {

    final CompressingRequestBody body =
        new CompressingRequestBody(compressionType, data::getStream);

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
            // Set chunked transfer
            .addHeader("Transfer-Encoding", "chunked")
            // Note: this header is used to disable tracing of profiling requests
            .addHeader(DATADOG_META_LANG, JAVA_LANG)
            .post(requestBody);
    if (agentless && apiKey != null) {
      // we only add the api key header if we know we're doing agentless profiling. No point in
      // adding it to other agent-based requests since we know the datadog-agent isn't going to
      // make use of it.
      requestBuilder.addHeader(HEADER_DD_API_KEY, apiKey);
    }
    if (containerId != null) {
      requestBuilder.addHeader(HEADER_DD_CONTAINER_ID, containerId);
    }
    client
        .newCall(requestBuilder.build())
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                logDebug("Failed to upload profile");
                responseCallback.onFailure(call, e);
                onCompletion.run();
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                logDebug("Uploaded profile");
                responseCallback.onResponse(call, response);
                onCompletion.run();
              }

              private void logDebug(String msg) {
                if (log.isDebugEnabled()) {
                  log.debug(
                      "{} {} [{}] (Size={}/{} bytes)",
                      msg,
                      data.getName(),
                      type,
                      body.getReadBytes(),
                      body.getWrittenBytes());
                }
              }
            });
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
