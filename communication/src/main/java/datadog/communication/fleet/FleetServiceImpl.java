package datadog.communication.fleet;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.util.AgentThreadFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.UndeclaredThrowableException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FleetServiceImpl implements FleetService {

  private static final Logger log = LoggerFactory.getLogger(FleetServiceImpl.class);
  private static final String CONFIG_PRODUCT_HEADER = "Datadog-Client-Config-Product";
  private static final int MAX_RESPONSE_SIZE = 8 * 1024 * 1024; // 8 MB

  private final SharedCommunicationObjects sco;
  private final Thread thread;

  // for testing
  volatile CountDownLatch testingLatch;

  private final ConcurrentMap<Product, FleetSubscriptionImpl> subscriptions =
      new ConcurrentHashMap<>();

  public FleetServiceImpl(SharedCommunicationObjects sco, AgentThreadFactory agentThreadFactory) {
    this.sco = sco;
    thread = agentThreadFactory.newThread(new AgentConfigPollingRunnable());
  }

  @Override
  public void init() {
    thread.start();
  }

  @Override
  public FleetService.FleetSubscription subscribe(
      Product product, final ConfigurationListener listener) {
    FleetSubscriptionImpl sub = new FleetSubscriptionImpl(product, listener);
    subscriptions.put(product, sub);
    return sub;
  }

  @Override
  public void close() throws IOException {
    thread.interrupt();
    try {
      thread.join(5000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted waiting for thread " + thread.getName() + "to join");
    }
  }

  private class AgentConfigPollingRunnable implements Runnable {
    private static final double BACKOFF_INITIAL = 3.0d;
    private static final double BACKOFF_BASE = 3.0d;
    private static final double BACKOFF_MAX_EXPONENT = 3.0d;

    private int consecutiveFailures;
    private OkHttpClient okHttpClient;
    private HttpUrl httpUrl;
    private MessageDigest digest;

    AgentConfigPollingRunnable() {
      try {
        digest = MessageDigest.getInstance("MD5");
      } catch (NoSuchAlgorithmException e) {
        throw new UndeclaredThrowableException(e);
      }
    }

    @Override
    public void run() {
      okHttpClient = sco.okHttpClient;
      httpUrl = sco.agentUrl.newBuilder().addPathSegment("v0.6").addPathSegment("config").build();

      if (testingLatch != null) {
        testingLatch.countDown();
      }
      while (!Thread.interrupted()) {
        try {
          boolean success = mainLoopIteration();
          if (testingLatch != null) {
            testingLatch.countDown();
          }
          if (success) {
            successWait();
          } else {
            failureWait();
          }
        } catch (InterruptedException e) {
          log.info("Interrupted; exiting");
          Thread.currentThread().interrupt();
        }
      }
    }

    private boolean mainLoopIteration() throws InterruptedException {
      boolean anySuccess = false;
      Collection<FleetSubscriptionImpl> subs = subscriptions.values();
      if (subs.isEmpty()) {
        return true;
      }

      for (FleetSubscriptionImpl sub : subs) {
        anySuccess |= fetchConfig(sub);
      }

      return anySuccess;
    }

    private boolean fetchConfig(FleetSubscriptionImpl sub) {

      Request request = OkHttpUtils.prepareRequest(httpUrl, sub.headers).get().build();
      Response response;
      try {
        response = okHttpClient.newCall(request).execute();
      } catch (IOException e) {
        log.warn("IOException on HTTP class to fleet service", e);
        return false;
      }

      if (response.code() == 200) {
        byte[] body;
        try {
          body = consumeBody(response);
        } catch (IOException e) {
          log.warn("IOException when reading fleet service response");
          return false;
        }

        digest.reset();
        byte[] hash = digest.digest(body);
        if (Arrays.equals(hash, sub.lastHash)) {
          return true;
        }

        sub.lastHash = hash;
        sub.listener.onNewConfiguration(new ByteArrayInputStream(body));

        return true;
      } else {
        log.warn("FleetService: agent responded with code " + response.code());
        return false;
      }
    }

    private void successWait() {
      consecutiveFailures = 0;
      int waitSeconds = 30;
      if (testingLatch != null && testingLatch.getCount() > 0) {
        waitSeconds = 0;
      }
      try {
        Thread.sleep(waitSeconds * 1000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private void failureWait() {
      double waitSeconds;
      consecutiveFailures++;
      waitSeconds =
          BACKOFF_INITIAL
              * Math.pow(
                  BACKOFF_BASE, Math.min((double) consecutiveFailures - 1, BACKOFF_MAX_EXPONENT));
      if (testingLatch != null && testingLatch.getCount() > 0) {
        waitSeconds = 0;
      }
      log.warn(
          "Last fleet management config fetching attempt failed; "
              + "will retry in {} seconds (num failures: {})",
          waitSeconds,
          consecutiveFailures);
      try {
        Thread.sleep((long) (waitSeconds * 1000L));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private byte[] consumeBody(Response response) throws IOException {
      ByteArrayOutputStream os = new ByteArrayOutputStream();

      InputStream inputStream = response.body().byteStream();

      byte[] buffer = new byte[8192];
      int read;
      while ((read = inputStream.read(buffer)) > 0) {
        os.write(buffer, 0, read);
        if (os.size() > MAX_RESPONSE_SIZE) {
          throw new IOException("MAX_RESPONSE_SIZE exceeded");
        }
      }

      return os.toByteArray();
    }
  }

  private class FleetSubscriptionImpl implements FleetService.FleetSubscription {
    private final Product product;
    private final Map<String, String> headers;
    private final ConfigurationListener listener;
    private byte[] lastHash;

    private FleetSubscriptionImpl(Product product, ConfigurationListener listener) {
      this.product = product;
      headers = Collections.singletonMap(CONFIG_PRODUCT_HEADER, product.name());
      this.listener = listener;
    }

    @Override
    public void cancel() {
      subscriptions.remove(product, this);
    }
  }
}
