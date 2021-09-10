package com.datadog.appsec.report;

import com.datadog.appsec.util.AppSecVersion;
import com.datadog.appsec.util.StandardizedLogging;
import com.squareup.moshi.JsonAdapter;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.monitor.Counter;
import datadog.communication.monitor.Monitoring;
import datadog.trace.util.AgentTaskScheduler;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppSecApi {
  private static final MediaType JSON = MediaType.get("application/json");
  private static final String DATADOG_META_APP_SEC_VERSION = "Datadog-Meta-AppSec-Version";
  private static final String X_API_VERSION = "X-Api-Version";
  private static final Logger log = LoggerFactory.getLogger(AppSecApi.class);
  private static final int MAX_UNFINISHED = 5;

  private final HttpUrl httpUrl;
  private final OkHttpClient okHttpClient;
  private final HashMap<String, String> headers;
  private final Counter counter;
  private final AgentTaskScheduler agentTaskScheduler;
  private final AgentTaskScheduler.Task<Request> task = new AppSecApiHttpTask();
  private final AtomicInteger unfinishedRequests = new AtomicInteger();

  public AppSecApi(
      Monitoring monitoring,
      HttpUrl httpUrl,
      OkHttpClient okHttpClient,
      AgentTaskScheduler taskScheduler) {
    this.httpUrl =
        httpUrl
            .newBuilder()
            .addPathSegment("appsec")
            .addPathSegment("proxy")
            .addPathSegment("api")
            .addPathSegment("v2")
            .addPathSegment("appsecevts")
            .build();
    this.okHttpClient = okHttpClient;
    this.headers = new HashMap<>();
    this.headers.put(DATADOG_META_APP_SEC_VERSION, AppSecVersion.VERSION);
    this.headers.put(X_API_VERSION, "v0.1.0");
    this.counter = monitoring.newCounter("appsec.batches.counter");
    this.agentTaskScheduler = taskScheduler;
  }

  public <T> void sendIntakeBatch(final T intakeBatch, final JsonAdapter<? super T> adapter) {
    RequestBody requestBody =
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return JSON;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            adapter.toJson(sink, intakeBatch);
          }
        };

    Request request =
        OkHttpUtils.prepareRequest(this.httpUrl, this.headers).post(requestBody).build();

    // the endpoint for /appsec is just a reverse proxy, so this
    // call involves communication over the network
    int curUnfinished;
    do {
      curUnfinished = unfinishedRequests.get();
      if (curUnfinished >= MAX_UNFINISHED) {
        counter.incrementErrorCount("Max queued requests reached", 1);
        log.warn(
            "Request to AppSec API not forwarded because the max number of "
                + "queued requests has been reached");
        return;
      }
    } while (!unfinishedRequests.compareAndSet(curUnfinished, curUnfinished + 1));

    agentTaskScheduler.schedule(task, request, 0, TimeUnit.MILLISECONDS);
  }

  private class AppSecApiHttpTask implements AgentTaskScheduler.Task<Request> {
    @Override
    public void run(Request request) {
      try {
        doRun(request);
      } finally {
        unfinishedRequests.decrementAndGet();
      }
    }

    private void doRun(Request request) {
      Response response;
      try {
        response = okHttpClient.newCall(request).execute();
      } catch (IOException e) {
        counter.incrementErrorCount(e.getMessage(), 1);
        StandardizedLogging.attackReportingFailed(log, e);
        return;
      }

      if (response.code() < 200 || response.code() > 299) {
        counter.incrementErrorCount(Integer.toString(response.code()), 1);
        ResponseBody body = response.body();
        String errorBody = "";
        if (body != null) {
          try {
            errorBody = body.string();
          } catch (IOException e) {
            errorBody = "Error getting body: " + e.getMessage();
          }
        }
        log.warn("Request to AppSec API failed with http code {}: {}", response.code(), errorBody);
      } else {
        counter.increment(1);
        log.debug("Request successful to AppSec API");
      }
    }
  }
}
