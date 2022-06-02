package com.datadog.debugger.poller;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles http requests for fetching debugger configuration */
class PollerHttpClient {
  static final String HEADER_DD_API_KEY = "DD-API-KEY";
  static final String HEADER_DEBUGGER_TRACKING_ID = "X-Datadog-HostId";
  private static final Logger LOGGER = LoggerFactory.getLogger(PollerHttpClient.class);
  private static final int MAX_RUNNING_REQUESTS = 1;
  private static final long TERMINATION_TIMEOUT = 5;

  private final ScheduledExecutorService okHttpExecutorService;
  private final OkHttpClient client;
  private final Request request;

  public PollerHttpClient(Request request, Duration requestTimeout) {
    this.request = request;
    // This is the same thing OkHttp Dispatcher is doing except thread naming and daemonization
    okHttpExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            new PollerThreadFactory("dd-debugger-poll-http-dispatcher"));
    // Reusing connections causes non daemon threads to be created which causes agent to prevent app
    // from exiting. See https://github.com/square/okhttp/issues/4029 for some details.
    ConnectionPool connectionPool = new ConnectionPool(MAX_RUNNING_REQUESTS, 1, TimeUnit.SECONDS);

    OkHttpClient.Builder clientBuilder =
        new OkHttpClient.Builder()
            .connectTimeout(requestTimeout)
            .writeTimeout(requestTimeout)
            .readTimeout(requestTimeout)
            .callTimeout(requestTimeout)
            .dispatcher(new Dispatcher(okHttpExecutorService))
            .connectionPool(connectionPool);

    if (!request.url().isHttps()) {
      // force clear text when using http to avoid failures for JVMs without TLS
      // see: https://github.com/DataDog/dd-trace-java/pull/1582
      clientBuilder.connectionSpecs(Collections.singletonList(ConnectionSpec.CLEARTEXT));
    }
    client = clientBuilder.build();
    client.dispatcher().setMaxRequests(MAX_RUNNING_REQUESTS);
    // We are mainly talking to the same(ish) host, so we need to raise this limit
    client.dispatcher().setMaxRequestsPerHost(MAX_RUNNING_REQUESTS);
  }

  Response fetchConfiguration() throws IOException {
    return client.newCall(request).execute();
  }

  public Request getRequest() {
    return request;
  }

  OkHttpClient getClient() {
    return client;
  }

  void stop() {
    okHttpExecutorService.shutdownNow();
    try {
      okHttpExecutorService.awaitTermination(TERMINATION_TIMEOUT, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      // Note: this should only happen in main thread right before exiting, so eating up interrupted
      // state should be fine.
      LOGGER.warn("Wait for executor shutdown interrupted");
    }
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
  }
  // FIXME: we should unify all thread factories in common library
  static class PollerThreadFactory implements ThreadFactory {
    private final String name;

    public PollerThreadFactory(final String name) {
      this.name = name;
    }

    @Override
    public Thread newThread(final Runnable r) {
      final Thread t = new Thread(r, name);
      t.setDaemon(true);
      return t;
    }
  }
}
