package com.datadog.debugger.uploader;

import static datadog.trace.util.AgentThreadFactory.AgentThread.DEBUGGER_HTTP_DISPATCHER;

import com.datadog.debugger.util.DebuggerMetrics;
import datadog.common.container.ContainerInfo;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.relocate.api.RatelimitedLogger;
import datadog.trace.util.AgentThreadFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Phaser;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles batching logic of upload requests sent to the intake */
public class BatchUploader {
  public static class MultiPartContent {
    private final byte[] content;
    private final String partName;
    private final String fileName;
    private final MediaType mediaType;

    public MultiPartContent(byte[] content, String partName, String fileName, MediaType mediaType) {
      this.content = content;
      this.partName = partName;
      this.fileName = fileName;
      this.mediaType = mediaType;
    }

    public byte[] getContent() {
      return content;
    }

    public String getPartName() {
      return partName;
    }

    public String getFileName() {
      return fileName;
    }

    public MediaType getMediaType() {
      return mediaType;
    }
  }

  public static class RetryPolicy {
    public final ConcurrentMap<Call, Integer> failures = new ConcurrentHashMap<>();
    public final int maxFailures;

    public RetryPolicy(int maxFailures) {
      this.maxFailures = maxFailures;
    }
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(BatchUploader.class);
  private static final int MINUTES_BETWEEN_ERROR_LOG = 5;
  private static final String HEADER_DD_CONTAINER_ID = "Datadog-Container-ID";
  private static final String HEADER_DD_ENTITY_ID = "Datadog-Entity-ID";
  static final String HEADER_DD_API_KEY = "DD-API-KEY";
  static final int MAX_RUNNING_REQUESTS = 10;
  public static final int MAX_ENQUEUED_REQUESTS = 20;
  static final int TERMINATION_TIMEOUT = 5;
  public static final MediaType APPLICATION_JSON = MediaType.get("application/json");
  public static final MediaType APPLICATION_GZIP = MediaType.get("application/gzip");

  private final String name;
  private final String containerId;
  private final String entityId;
  private final ExecutorService okHttpExecutorService;
  private final OkHttpClient client;
  private final HttpUrl urlBase;
  private final Callback responseCallback;
  private final String apiKey;
  private final DebuggerMetrics debuggerMetrics;
  private final boolean instrumentTheWorld;
  private final RatelimitedLogger ratelimitedLogger;
  private final RetryPolicy retryPolicy;

  private final Phaser inflightRequests = new Phaser(1);

  public BatchUploader(String name, Config config, String endpoint, RetryPolicy retryPolicy) {
    this(
        name,
        config,
        endpoint,
        new RatelimitedLogger(LOGGER, MINUTES_BETWEEN_ERROR_LOG, TimeUnit.MINUTES),
        retryPolicy);
  }

  BatchUploader(
      String name,
      Config config,
      String endpoint,
      RatelimitedLogger ratelimitedLogger,
      RetryPolicy retryPolicy) {
    this(
        name,
        config,
        endpoint,
        ratelimitedLogger,
        retryPolicy,
        ContainerInfo.get().containerId,
        ContainerInfo.getEntityId());
  }

  // Visible for testing
  BatchUploader(
      String name,
      Config config,
      String endpoint,
      RatelimitedLogger ratelimitedLogger,
      RetryPolicy retryPolicy,
      String containerId,
      String entityId) {
    this.name = name;
    instrumentTheWorld = config.getDynamicInstrumentationInstrumentTheWorld() != null;
    if (endpoint == null || endpoint.length() == 0) {
      throw new IllegalArgumentException("Endpoint url is empty");
    }
    urlBase = HttpUrl.get(endpoint);
    LOGGER.debug("Started BatchUploader[{}] with target url {}", name, urlBase);
    apiKey = config.getApiKey();
    this.ratelimitedLogger = ratelimitedLogger;
    // This is the same thing OkHttp Dispatcher is doing except thread naming and daemonization
    okHttpExecutorService =
        new ThreadPoolExecutor(
            0,
            Integer.MAX_VALUE,
            60,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new AgentThreadFactory(DEBUGGER_HTTP_DISPATCHER));
    this.retryPolicy = retryPolicy;
    this.containerId = containerId;
    this.entityId = entityId;
    Duration requestTimeout = Duration.ofSeconds(config.getDynamicInstrumentationUploadTimeout());
    client =
        OkHttpUtils.buildHttpClient(
            config,
            new Dispatcher(okHttpExecutorService),
            urlBase,
            true, /* retry */
            MAX_RUNNING_REQUESTS,
            null, /* proxyHost */
            null, /* proxyPort */
            null, /* proxyUsername */
            null, /* proxyPassword */
            requestTimeout.toMillis());
    responseCallback =
        new ResponseCallback(name, ratelimitedLogger, inflightRequests, client, retryPolicy);
    debuggerMetrics = DebuggerMetrics.getInstance(config);
  }

  public void upload(byte[] batch) {
    upload(batch, "");
  }

  public void upload(byte[] batch, String tags) {
    doUpload(() -> makeUploadRequest(batch, tags));
  }

  public void uploadAsMultipart(String tags, MultiPartContent... parts) {
    doUpload(() -> makeMultipartUploadRequest(tags, parts));
  }

  private void makeMultipartUploadRequest(String tags, MultiPartContent[] parts) {
    MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
    int contentLength = 0;
    for (MultiPartContent part : parts) {
      contentLength += addPart(builder, part);
    }
    MultipartBody body = builder.build();
    buildAndSendRequest(body, contentLength, tags);
  }

  private int addPart(MultipartBody.Builder builder, MultiPartContent part) {
    RequestBody fileBody = RequestBody.create(part.mediaType, part.content);
    builder.addFormDataPart(part.partName, part.fileName, fileBody);
    return part.content.length;
  }

  private void doUpload(Runnable makeRequest) {
    if (instrumentTheWorld) {
      // no upload in Instrument-The-World mode
      return;
    }
    try {
      if (canEnqueueMoreRequests()) {
        makeRequest.run();
        debuggerMetrics.count("batch.uploaded", 1);
      } else {
        debuggerMetrics.count("request.queue.full", 1);
        ratelimitedLogger.warn(
            "Cannot upload batch data to {}: too many enqueued requests!", urlBase);
      }
    } catch (Exception ex) {
      debuggerMetrics.count("batch.upload.error", 1);
      ratelimitedLogger.warn("Problem uploading batch!", ex);
    }
  }

  /**
   * Note that this method is only visible for testing and should not be used from outside this
   * class.
   */
  OkHttpClient getClient() {
    return client;
  }

  public HttpUrl getUrl() {
    return urlBase;
  }

  RetryPolicy getRetryPolicy() {
    return retryPolicy;
  }

  private void makeUploadRequest(byte[] json, String tags) {
    int contentLength = json.length;
    // use RequestBody.create(MediaType, byte[]) to avoid changing Content-Type to
    // "Content-Type: application/json; charset=UTF-8" which is not recognized
    RequestBody body = RequestBody.create(APPLICATION_JSON, json);
    buildAndSendRequest(body, contentLength, tags);
  }

  private void buildAndSendRequest(RequestBody body, int contentLength, String tags) {
    debuggerMetrics.histogram("batch.uploader.request.size", contentLength);
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("[{}] Uploading batch data size={} bytes", name, contentLength);
    }
    HttpUrl.Builder builder = urlBase.newBuilder();
    if (tags != null && !tags.isEmpty()) {
      builder.addQueryParameter("ddtags", tags);
    }
    Request.Builder requestBuilder = new Request.Builder().url(builder.build()).post(body);
    if (apiKey != null) {
      if (apiKey.isEmpty()) {
        LOGGER.debug("API key is empty");
      }
      if (apiKey.length() != 32) {
        LOGGER.debug(
            "API key length is incorrect (truncated?) expected=32 actual={} API key={}...",
            apiKey.length(),
            apiKey.substring(0, Math.min(apiKey.length(), 6)));
      }
      requestBuilder.addHeader(HEADER_DD_API_KEY, apiKey);
    } else {
      LOGGER.debug("API key is null");
    }
    if (containerId != null) {
      requestBuilder.addHeader(HEADER_DD_CONTAINER_ID, containerId);
    }
    if (entityId != null) {
      requestBuilder.addHeader(HEADER_DD_ENTITY_ID, entityId);
    }
    Request request = requestBuilder.build();
    LOGGER.debug("[{}] Sending request: {} CT: {}", name, request, request.body().contentType());
    enqueueCall(client, request, responseCallback, retryPolicy, 0, inflightRequests);
  }

  public void shutdown() {
    try {
      inflightRequests.awaitAdvanceInterruptibly(inflightRequests.arrive(), 10, TimeUnit.SECONDS);
    } catch (TimeoutException | InterruptedException ignored) {
      LOGGER.warn("[{}] Not all upload requests have been handled", name);
    }
    okHttpExecutorService.shutdownNow();
    try {
      okHttpExecutorService.awaitTermination(TERMINATION_TIMEOUT, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      // Note: this should only happen in main thread right before exiting, so eating up interrupted
      // state should be fine.
      LOGGER.warn("[{}] Wait for executor shutdown interrupted", name);
    }
    client.connectionPool().evictAll();
  }

  private boolean canEnqueueMoreRequests() {
    return client.dispatcher().queuedCallsCount() < MAX_ENQUEUED_REQUESTS;
  }

  private static void enqueueCall(
      OkHttpClient client,
      Request request,
      Callback responseCallback,
      RetryPolicy retryPolicy,
      int failureCount,
      Phaser inflightRequests) {
    Call call = client.newCall(request);
    retryPolicy.failures.put(call, failureCount);
    call.enqueue(responseCallback);
    inflightRequests.register();
  }

  private static final class ResponseCallback implements Callback {

    private final String name;
    private final RatelimitedLogger ratelimitedLogger;
    private final Phaser inflightRequests;
    private final OkHttpClient client;
    private final RetryPolicy retryPolicy;

    public ResponseCallback(
        String name,
        final RatelimitedLogger ratelimitedLogger,
        Phaser inflightRequests,
        OkHttpClient client,
        RetryPolicy retryPolicy) {
      this.name = name;
      this.ratelimitedLogger = ratelimitedLogger;
      this.inflightRequests = inflightRequests;
      this.client = client;
      this.retryPolicy = retryPolicy;
    }

    @Override
    public void onFailure(Call call, IOException e) {
      inflightRequests.arriveAndDeregister();
      ratelimitedLogger.warn("Failed to upload batch to {}", call.request().url(), e);
      handleRetry(call, retryPolicy.maxFailures);
    }

    private void handleRetry(Call call, int maxFailures) {
      Integer failure = retryPolicy.failures.remove(call);
      if (failure != null) {
        int failureCount = failure + 1;
        if (failureCount <= maxFailures) {
          LOGGER.debug(
              "[{}] Retrying upload to {}, {}/{}",
              name,
              call.request().url(),
              failureCount,
              maxFailures);
          enqueueCall(client, call.request(), this, retryPolicy, failureCount, inflightRequests);
        } else {
          LOGGER.warn(
              "[{}] Failed permanently to upload batch to {} after {} attempts",
              name,
              call.request().url(),
              maxFailures);
        }
      }
    }

    @Override
    public void onResponse(Call call, Response response) {
      try {
        inflightRequests.arriveAndDeregister();
        if (response.isSuccessful()) {
          LOGGER.debug("[{}] Upload done", name);
          retryPolicy.failures.remove(call);
        } else {
          ResponseBody body = response.body();
          // Retrieve body content for detailed error messages
          if (body != null && MediaType.get("application/json").equals(body.contentType())) {
            try {
              ratelimitedLogger.warn(
                  "Failed to upload batch: unexpected response code {} {} {}",
                  response.message(),
                  response.code(),
                  body.string());
            } catch (IOException ex) {
              ratelimitedLogger.warn("error while getting error message body", ex);
            }
          } else {
            ratelimitedLogger.warn(
                "Failed to upload batch: unexpected response code {} {}",
                response.message(),
                response.code());
          }
          if (response.code() >= 500 || response.code() == 408 || response.code() == 429) {
            handleRetry(call, retryPolicy.maxFailures);
          } else {
            retryPolicy.failures.remove(call);
          }
        }
      } finally {
        response.close();
      }
    }
  }
}
