package com.datadog.debugger.uploader;

import static datadog.http.client.HttpRequest.APPLICATION_JSON;
import static datadog.http.client.HttpRequest.CONTENT_TYPE;
import static datadog.trace.util.AgentThreadFactory.AgentThread.DEBUGGER_HTTP_DISPATCHER;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.debugger.util.DebuggerMetrics;
import datadog.common.container.ContainerInfo;
import datadog.communication.http.HttpUtils;
import datadog.http.client.HttpClient;
import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestBody;
import datadog.http.client.HttpUrl;
import datadog.trace.api.Config;
import datadog.trace.relocate.api.RatelimitedLogger;
import datadog.trace.util.AgentThreadFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Phaser;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles batching logic of upload requests sent to the intake */
public class BatchUploader {
  public static class MultiPartContent {
    private final byte[] content;
    private final String partName;
    private final String fileName;
    private final String contentType;

    public MultiPartContent(byte[] content, String partName, String fileName, String contentType) {
      this.content = content;
      this.partName = partName;
      this.fileName = fileName;
      this.contentType = contentType;
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

    public String getContentType() {
      return contentType;
    }
  }

  public static class RetryPolicy {
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
  public static final String APPLICATION_JSON = "application/json";
  public static final String APPLICATION_GZIP = "application/gzip";

  private final String name;
  private final String containerId;
  private final String entityId;
  private final ExecutorService executorService;
  private final HttpClient client;
  private final HttpUrl urlBase;
  private final String apiKey;
  private final DebuggerMetrics debuggerMetrics;
  private final boolean instrumentTheWorld;
  private final RatelimitedLogger ratelimitedLogger;
  private final RetryPolicy retryPolicy;

  private final Phaser inflightRequests = new Phaser(1);
  private final Semaphore runningRequests = new Semaphore(MAX_RUNNING_REQUESTS);
  private final AtomicInteger queuedRequestsCount = new AtomicInteger(0);

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
    if (endpoint == null || endpoint.isEmpty()) {
      throw new IllegalArgumentException("Endpoint url is empty");
    }
    urlBase = HttpUrl.parse(endpoint);
    LOGGER.debug("Started BatchUploader[{}] with target url {}", name, urlBase);
    apiKey = config.getApiKey();
    this.ratelimitedLogger = ratelimitedLogger;
    // Thread pool for async request execution
    executorService =
        new ThreadPoolExecutor(
            0,
            MAX_RUNNING_REQUESTS,
            60,
            SECONDS,
            new SynchronousQueue<>(),
            new AgentThreadFactory(DEBUGGER_HTTP_DISPATCHER));
    this.retryPolicy = retryPolicy;
    this.containerId = containerId;
    this.entityId = entityId;
    Duration requestTimeout = Duration.ofSeconds(config.getDynamicInstrumentationUploadTimeout());
    client =
        HttpUtils.buildHttpClient(
            config,
            executorService,
            urlBase,
            null, /* proxyHost */
            null, /* proxyPort */
            null, /* proxyUsername */
            null, /* proxyPassword */
            requestTimeout.toMillis());
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
    HttpRequestBody.MultipartBuilder multipartBuilder = HttpRequestBody.multipart();
    int contentLength = 0;
    for (MultiPartContent part : parts) {
      HttpRequestBody partBody = HttpRequestBody.of(part.content);
      multipartBuilder.addFormDataPart(part.partName, part.fileName, partBody);
      contentLength += part.content.length;
    }
    String contentType = multipartBuilder.contentType();
    HttpRequestBody body = multipartBuilder.build();
    buildAndSendRequest(body, contentType, contentLength, tags);
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
  HttpClient getClient() {
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
    HttpRequestBody body = HttpRequestBody.of(json);
    // use custom Content-Type instead of the default
    // "Content-Type: application/json; charset=UTF-8" which is not recognized
    buildAndSendRequest(body, APPLICATION_JSON, contentLength, tags);
  }

  private void buildAndSendRequest(
      HttpRequestBody body, String contentType, int contentLength, String tags) {
    debuggerMetrics.histogram("batch.uploader.request.size", contentLength);
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("[{}] Uploading batch data size={} bytes", name, contentLength);
    }
    HttpUrl.Builder builder = urlBase.newBuilder();
    if (tags != null && !tags.isEmpty()) {
      builder.addQueryParameter("ddtags", tags);
    }
    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder().url(builder.build()).header(CONTENT_TYPE, contentType).post(body);
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
    HttpRequest request = requestBuilder.build();
    LOGGER.debug("[{}] Sending request: {} CT: {}", name, request, contentType);
    enqueueRequest(request, 0);
  }

  public void shutdown() {
    try {
      inflightRequests.awaitAdvanceInterruptibly(inflightRequests.arrive(), 10, SECONDS);
    } catch (TimeoutException | InterruptedException ignored) {
      LOGGER.warn("[{}] Not all upload requests have been handled", name);
    }
    executorService.shutdownNow();
    try {
      executorService.awaitTermination(TERMINATION_TIMEOUT, SECONDS);
    } catch (final InterruptedException e) {
      // Note: this should only happen in main thread right before exiting, so eating up interrupted
      // state should be fine.
      LOGGER.warn("[{}] Wait for executor shutdown interrupted", name);
    }
  }

  private boolean canEnqueueMoreRequests() {
    return queuedRequestsCount.get() < MAX_ENQUEUED_REQUESTS;
  }

  private void enqueueRequest(HttpRequest request, int failureCount) {
    queuedRequestsCount.incrementAndGet();
    inflightRequests.register();
    executorService.submit(
        () -> {
          try {
            // Wait for a permit to limit concurrent running requests
            runningRequests.acquire();
            executeRequestAsync(request, failureCount);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            inflightRequests.arriveAndDeregister();
            queuedRequestsCount.decrementAndGet();
          }
        });
  }

  private void executeRequestAsync(HttpRequest request, int failureCount) {
    client
        .executeAsync(request)
        .whenComplete(
            (response, throwable) -> {
              try {
                if (throwable != null) {
                  ratelimitedLogger.warn("Failed to upload batch to {}", request.url(), throwable);
                  handleRetry(request, failureCount);
                } else if (response.isSuccessful()) {
                  LOGGER.debug("[{}] Upload done", name);
                } else {
                  String responseBody = null;
                  try {
                    responseBody = response.bodyAsString();
                  } catch (IOException ignored) {
                    // Ignore error reading body
                  }
                  if (responseBody != null && !responseBody.isEmpty()) {
                    ratelimitedLogger.warn(
                        "Failed to upload batch: unexpected response code {} {}",
                        response.code(),
                        responseBody);
                  } else {
                    ratelimitedLogger.warn(
                        "Failed to upload batch: unexpected response code {}", response.code());
                  }
                  // Retry on server errors, timeout, or rate limiting
                  if (response.code() >= 500 || response.code() == 408 || response.code() == 429) {
                    handleRetry(request, failureCount);
                  }
                }
              } finally {
                if (response != null) {
                  response.close();
                }
                runningRequests.release();
                queuedRequestsCount.decrementAndGet();
                inflightRequests.arriveAndDeregister();
              }
            });
  }

  private void handleRetry(HttpRequest request, int failureCount) {
    int newFailureCount = failureCount + 1;
    if (newFailureCount <= retryPolicy.maxFailures) {
      LOGGER.debug(
          "[{}] Retrying upload to {}, {}/{}",
          name,
          request.url(),
          newFailureCount,
          retryPolicy.maxFailures);
      enqueueRequest(request, newFailureCount);
    } else {
      LOGGER.warn(
          "[{}] Failed permanently to upload batch to {} after {} attempts",
          name,
          request.url(),
          retryPolicy.maxFailures);
    }
  }
}
