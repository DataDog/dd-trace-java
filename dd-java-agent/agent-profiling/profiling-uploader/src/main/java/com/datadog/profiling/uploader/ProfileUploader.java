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

import com.datadog.profiling.uploader.util.JfrCliHelper;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import datadog.common.container.ServerlessInfo;
import datadog.common.version.VersionInfo;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.Platform;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingType;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.relocate.api.IOLogger;
import datadog.trace.util.AgentThreadFactory;
import datadog.trace.util.PidHelper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The class for uploading profiles to the backend. */
public final class ProfileUploader {

  private static final Logger log = LoggerFactory.getLogger(ProfileUploader.class);
  private static final MediaType APPLICATION_JSON = MediaType.get("application/json");
  private static final int TERMINATION_TIMEOUT_SEC = 5;

  static final int MAX_RUNNING_REQUESTS = 10;
  static final int MAX_ENQUEUED_REQUESTS = 20;

  // V2.4 format
  static final String V4_PROFILE_TAGS_PARAM = "tags_profiler";
  static final String V4_PROFILE_START_PARAM = "start";
  static final String V4_PROFILE_END_PARAM = "end";
  static final String V4_VERSION = "4";
  static final String V4_FAMILY = "java";

  static final String V4_EVENT_NAME = "event";
  static final String V4_EVENT_FILENAME = V4_EVENT_NAME + ".json";
  static final String V4_ATTACHMENT_NAME = "main";
  static final String V4_ATTACHMENT_FILENAME = V4_ATTACHMENT_NAME + ".jfr";

  // Header names and values
  private static final String HEADER_DD_EVP_ORIGIN = "DD-EVP-ORIGIN";
  private static final String HEADER_DD_EVP_ORIGIN_VERSION = "DD-EVP-ORIGIN-VERSION";

  private static final String JAVA_TRACING_LIBRARY = "dd-trace-java";

  private static final Headers EVENT_HEADER =
      Headers.of(
          "Content-Disposition",
          "form-data; name=\"" + V4_EVENT_NAME + "\"; filename=\"" + V4_EVENT_FILENAME + "\"");

  private static final Headers V4_DATA_HEADERS =
      Headers.of(
          "Content-Disposition",
          "form-data; name=\""
              + V4_ATTACHMENT_NAME
              + "\"; filename=\""
              + V4_ATTACHMENT_FILENAME
              + "\"");

  static final String SERVELESS_TAG = "functionname";

  private final Config config;
  private final ConfigProvider configProvider;

  private final ExecutorService okHttpExecutorService;
  private final OkHttpClient client;
  private final IOLogger ioLogger;
  private final boolean agentless;
  private final boolean summaryOn413;
  private final HttpUrl url;
  private final int terminationTimeout;
  private final CompressionType compressionType;

  private final RecordingDataAdapter jsonAdapter;

  private final Duration uploadTimeout;

  public ProfileUploader(final Config config, final ConfigProvider configProvider) {
    this(config, configProvider, new IOLogger(log), TERMINATION_TIMEOUT_SEC);
  }

  /**
   * Note that this method is only visible for testing and should not be used from outside this
   * class.
   */
  ProfileUploader(
      final Config config,
      final ConfigProvider configProvider,
      final IOLogger ioLogger,
      final int terminationTimeout) {
    this.config = config;
    this.configProvider = configProvider;
    this.ioLogger = ioLogger;
    this.terminationTimeout = terminationTimeout;

    url = HttpUrl.get(config.getFinalProfilingUrl());
    agentless = config.isProfilingAgentless();
    summaryOn413 = config.isProfilingUploadSummaryOn413Enabled();

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
    tagsMap.put(VersionInfo.LIBRARY_VERSION_TAG, VersionInfo.VERSION);
    // PID can be empty if we cannot find it out from the system
    if (!PidHelper.getPid().isEmpty()) {
      tagsMap.put(DDTags.PID_TAG, PidHelper.getPid());
    }

    if (config.isTraceGitMetadataEnabled()) {
      GitInfo gitInfo = GitInfoProvider.INSTANCE.getGitInfo();
      tagsMap.put(Tags.GIT_REPOSITORY_URL, gitInfo.getRepositoryURL());
      tagsMap.put(Tags.GIT_COMMIT_SHA, gitInfo.getCommit().getSha());
    }
    if (Platform.isNativeImage()) {
      tagsMap.put(DDTags.RUNTIME_VERSION_TAG, tagsMap.get(DDTags.RUNTIME_VERSION_TAG) + "-aot");
    }
    if (ServerlessInfo.get().isRunningInServerlessEnvironment()) {
      tagsMap.put(SERVELESS_TAG, ServerlessInfo.get().getFunctionName());
    }

    // Comma separated tags string for V2.4 format
    Pattern quotes = Pattern.compile("\"");
    jsonAdapter =
        new RecordingDataAdapter(
            quotes.matcher(String.join(",", tagsToList(tagsMap))).replaceAll(""));
    uploadTimeout = Duration.ofSeconds(config.getProfilingUploadTimeout());

    // This is the same thing OkHttp Dispatcher is doing except thread naming and daemonization
    okHttpExecutorService =
        new ThreadPoolExecutor(
            0,
            Integer.MAX_VALUE,
            60,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new AgentThreadFactory(PROFILER_HTTP_DISPATCHER));

    client =
        OkHttpUtils.buildHttpClient(
            config,
            new Dispatcher(okHttpExecutorService),
            url,
            true,
            MAX_RUNNING_REQUESTS,
            config.getProfilingProxyHost(),
            config.getProfilingProxyPort(),
            config.getProfilingProxyUsername(),
            config.getProfilingProxyPassword(),
            uploadTimeout.toMillis());

    compressionType = CompressionType.of(config.getProfilingUploadCompression());
  }

  /**
   * Enqueue an upload request. Do not receive any notification when the upload has been completed.
   *
   * @param type {@link RecordingType recording type}
   * @param data {@link RecordingData recording data}
   */
  public void upload(final RecordingType type, final RecordingData data) {
    upload(type, data, false);
  }

  /**
   * Enqueue an upload request. Do not receive any notification when the upload has been completed.
   *
   * @param type {@link RecordingType recording type}
   * @param data {@link RecordingData recording data}
   * @param sync {@link boolean uploading synchronously}
   */
  public void upload(final RecordingType type, final RecordingData data, final boolean sync) {
    upload(type, data, sync, () -> {});
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
      final RecordingType type, final RecordingData data, @Nonnull final Runnable onCompletion) {
    upload(type, data, false, onCompletion);
  }

  /**
   * Enqueue an upload request and run the provided hook when that request is completed
   * (successfully or failing).
   *
   * @param type {@link RecordingType recording type}
   * @param data {@link RecordingData recording data}
   * @param sync {@link boolean uploading synchronously}
   * @param onCompletion call-back to execute once the request is completed (successfully or
   *     failing)
   */
  public void upload(
      final RecordingType type,
      final RecordingData data,
      final boolean sync,
      @Nonnull final Runnable onCompletion) {
    if (!canEnqueueMoreRequests()) {
      log.warn("Cannot upload profile data: too many enqueued requests!");
      // the request was not made; release the recording data
      data.release();
      return;
    }

    Call call = makeRequest(type, data);
    CountDownLatch latch = new CountDownLatch(sync ? 1 : 0);
    AtomicBoolean handled = new AtomicBoolean(false);

    call.enqueue(
        new Callback() {
          @Override
          public void onResponse(final Call call, final Response response) throws IOException {
            if (handled.compareAndSet(false, true)) {
              handleResponse(call, response, data, onCompletion);
              latch.countDown();
            }
          }

          @Override
          public void onFailure(final Call call, final IOException e) {
            if (handled.compareAndSet(false, true)) {
              handleFailure(call, e, data, onCompletion);
              latch.countDown();
            }
          }
        });
    if (sync) {
      try {
        log.debug("Waiting at most {} seconds for upload to finish", uploadTimeout.plusSeconds(1));
        if (!latch.await(uploadTimeout.plusSeconds(1).toMillis(), TimeUnit.MILLISECONDS)) {
          // Usually this should not happen and timeouts should be handled by the client.
          // But, in any case, we have this safety-break in place to prevent blocking finishing the
          // sync request to a misbehaving server.
          if (handled.compareAndSet(false, true)) {
            handleFailure(call, null, data, onCompletion);
          }
        }
      } catch (InterruptedException e) {
        if (handled.compareAndSet(false, true)) {
          handleFailure(call, e, data, onCompletion);
        }
        // reset the interrupted flag
        Thread.currentThread().interrupt();
      }
    }
  }

  private void handleFailure(
      final Call call,
      final Exception e,
      final RecordingData data,
      @Nonnull final Runnable onCompletion) {
    if (isEmptyReplyFromServer(e)) {
      ioLogger.error(
          "Failed to upload profile, received empty reply from "
              + call.request().url()
              + " after uploading profile");
    } else {
      ioLogger.error("Failed to upload profile to " + call.request().url(), e);
    }

    data.release();
    onCompletion.run();
  }

  private void handleResponse(
      final Call call,
      final Response response,
      final RecordingData data,
      @Nonnull final Runnable onCompletion)
      throws IOException {
    if (response.isSuccessful()) {
      ioLogger.success("Upload done");
    } else {
      final String apiKey = call.request().header("DD-API-KEY");
      if (response.code() == 404 && apiKey == null) {
        // if no API key and not found error we assume we're sending to the agent
        ioLogger.error(
            "Failed to upload profile. Datadog Agent is not accepting profiles. Agent-based profiling deployments require Datadog Agent >= 7.20");
      } else if (response.code() == 413 && summaryOn413) {
        ioLogger.error(
            "Failed to upload profile, it's too big. Dumping information about the profile");
        JfrCliHelper.invokeOn(data, ioLogger);
      } else {
        ioLogger.error("Failed to upload profile", getLoggerResponse(response));
      }
    }

    // Note: this whole callback never touches body and would be perfectly happy even if
    // server never sends it.
    response.close();

    data.release();
    onCompletion.run();
  }

  @SuppressFBWarnings("DCN_NULLPOINTER_EXCEPTION")
  private IOLogger.Response getLoggerResponse(final okhttp3.Response response) {
    if (response != null) {
      try {
        final ResponseBody body = response.body();
        return new IOLogger.Response(
            response.code(), response.message(), body == null ? "<null>" : body.string().trim());
      } catch (final NullPointerException | IOException ignored) {
      }
    }
    return null;
  }

  private static boolean isEmptyReplyFromServer(final Exception e) {
    // The server in datadog-agent triggers 'unexpected end of stream' caused by
    // EOFException.
    // The MockWebServer in tests triggers an InterruptedIOException with SocketPolicy
    // NO_RESPONSE. This is because in tests we can't cleanly terminate the connection
    // on the
    // server side without resetting.
    return e != null
        && ((e instanceof InterruptedIOException)
            || (e.getCause() != null && e.getCause() instanceof java.io.EOFException));
  }

  public void shutdown() {
    okHttpExecutorService.shutdownNow();
    try {
      okHttpExecutorService.awaitTermination(terminationTimeout, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      // Note: this should only happen in main thread right before exiting, so eating up interrupted
      // state should be fine.
      log.debug("Wait for executor shutdown interrupted");
    }
    client.connectionPool().evictAll();
  }

  private byte[] createEvent(@Nonnull final RecordingData data) {
    return jsonAdapter.toJson(data).getBytes(StandardCharsets.UTF_8);
  }

  private MultipartBody makeRequestBody(
      @Nonnull final RecordingData data, final CompressingRequestBody body) {
    final MultipartBody.Builder bodyBuilder =
        new MultipartBody.Builder().setType(MultipartBody.FORM);

    final byte[] event = createEvent(data);
    final RequestBody eventBody = RequestBody.create(APPLICATION_JSON, event);
    bodyBuilder.addPart(EVENT_HEADER, eventBody);
    bodyBuilder.addPart(V4_DATA_HEADERS, body);
    return bodyBuilder.build();
  }

  private Call makeRequest(@Nonnull final RecordingType type, @Nonnull final RecordingData data) {

    final CompressingRequestBody body =
        new CompressingRequestBody(compressionType, data::getStream);
    final RequestBody requestBody = makeRequestBody(data, body);

    final Map<String, String> headers = new HashMap<>();
    // Set chunked transfer
    headers.put("Transfer-Encoding", "chunked");
    headers.put(HEADER_DD_EVP_ORIGIN, JAVA_TRACING_LIBRARY);
    headers.put(HEADER_DD_EVP_ORIGIN_VERSION, VersionInfo.VERSION);

    return client.newCall(
        OkHttpUtils.prepareRequest(url, headers, config, agentless).post(requestBody).build());
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

  /**
   * Note that this method is only visible for testing and should not be used from outside this
   * class.
   */
  OkHttpClient getClient() {
    return client;
  }

  private static final class RecordingDataAdapter extends JsonAdapter<RecordingData> {

    private final String tags;

    private RecordingDataAdapter(String tags) {
      this.tags = tags;
    }

    @Nullable
    @Override
    public RecordingData fromJson(JsonReader jsonReader) {
      throw new IllegalStateException();
    }

    @Override
    public void toJson(JsonWriter writer, RecordingData recordingData) throws IOException {
      if (recordingData == null) {
        return;
      }
      final CharSequence processTags = ProcessTags.getTagsForSerialization();
      writer.beginObject();
      writer.name("attachments");
      writer.beginArray();
      writer.value(V4_ATTACHMENT_FILENAME);
      writer.endArray();
      writer.name(V4_PROFILE_TAGS_PARAM);
      writer.value(tags + ",snapshot:" + recordingData.getKind().name().toLowerCase(Locale.ROOT));
      if (processTags != null) {
        writer.name("process_tags");
        writer.value(processTags.toString());
      }
      writer.name(V4_PROFILE_START_PARAM);
      writer.value(recordingData.getStart().toString());
      writer.name(V4_PROFILE_END_PARAM);
      writer.value(recordingData.getEnd().toString());
      writer.name("family");
      writer.value(V4_FAMILY);
      writer.name("version");
      writer.value(V4_VERSION);
      writer.endObject();
    }
  }
}
