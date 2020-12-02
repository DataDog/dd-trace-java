package datadog.trace.common.pipeline;

import static datadog.trace.common.pipeline.EventListener.EventType.BAD_PAYLOAD;
import static datadog.trace.common.pipeline.EventListener.EventType.DOWNGRADED;
import static datadog.trace.common.pipeline.EventListener.EventType.ERROR;
import static datadog.trace.common.pipeline.EventListener.EventType.OK;
import static datadog.trace.core.http.OkHttpUtils.buildHttpClient;
import static datadog.trace.core.http.OkHttpUtils.msgpackRequestBodyOf;
import static datadog.trace.core.http.OkHttpUtils.prepareRequest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Slf4j
public final class OkHttpSink implements Sink, EventListener {

  public interface RequestCustomizer {
    Request.Builder customize(int messageCount, Request.Builder requestTemplate);
  }

  private static final RequestCustomizer NO_CUSTOMIZATION =
      new RequestCustomizer() {
        @Override
        public Request.Builder customize(int messageCount, Request.Builder requestTemplate) {
          return requestTemplate;
        }
      };

  private final OkHttpClient client;
  private final List<EventListener> listeners;
  private final RequestCustomizer requestCustomizer;
  private final String agentUrl;
  private final String[] paths;

  private volatile Request.Builder requestTemplate;

  public OkHttpSink(String agentUrl, long timeoutMillis, String... paths) {
    this(NO_CUSTOMIZATION, buildHttpClient(HttpUrl.get(agentUrl), timeoutMillis), agentUrl, paths);
  }

  public OkHttpSink(OkHttpClient client, String agentUrl, String... paths) {
    this(NO_CUSTOMIZATION, client, agentUrl, paths);
  }

  public OkHttpSink(
      RequestCustomizer requestCustomizer, OkHttpClient client, String agentUrl, String... paths) {
    this.client = client;
    this.agentUrl = agentUrl;
    this.paths = paths;
    this.listeners = new CopyOnWriteArrayList<>();
    this.requestCustomizer = requestCustomizer;
  }

  @Override
  public void accept(int messageCount, ByteBuffer... buffers) {
    if (!agentDiscovered()) {
      return;
    }
    try (final okhttp3.Response response =
        client
            .newCall(
                requestCustomizer
                    .customize(messageCount, requestTemplate)
                    .put(msgpackRequestBodyOf(buffers))
                    .build())
            .execute()) {
      if (!response.isSuccessful()) {
        handleFailure(response);
      } else {
        onEvent(OK, "");
      }
    } catch (IOException e) {
      onEvent(ERROR, e.getMessage());
    }
  }

  @Override
  public void onEvent(EventListener.EventType eventType, String message) {
    for (EventListener listener : listeners) {
      listener.onEvent(eventType, message);
    }
  }

  @Override
  public void register(EventListener listener) {
    this.listeners.add(listener);
  }

  private void handleFailure(okhttp3.Response response) {
    final int code = response.code();
    if (code == 404) {
      discover();
      onEvent(DOWNGRADED, "could not find endpoint");
    } else if (code >= 400 && code < 500) {
      onEvent(BAD_PAYLOAD, responseBody(response));
    } else if (code >= 500) {
      onEvent(ERROR, responseBody(response));
    }
  }

  private boolean agentDiscovered() {
    if (null == requestTemplate) {
      this.requestTemplate = discover();
    }
    return null != requestTemplate;
  }

  private Request.Builder discover() {
    for (String path : paths) {
      Request.Builder request = prepareRequest(HttpUrl.get(agentUrl).resolve(path));
      try (final Response response = client.newCall(request.build()).execute()) {
        int code = response.code();
        if (code == 404) {
          log.debug("{}/{} not found, keep probing", agentUrl, path);
          continue;
        }
        if (code == 503) {
          log.debug("no connectivity to {}: {}", agentUrl, responseBody(response));
          break;
        }
      } catch (IOException ignored) {
      }
      log.debug("selected {}/{}", agentUrl, path);
      return request;
    }
    return null;
  }

  private static String responseBody(Response response) {
    try {
      if (null != response.body()) {
        return response.body().string();
      }
    } catch (IOException ignore) {
    }
    return "";
  }
}
