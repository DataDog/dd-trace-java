package datadog.environment;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import okio.BufferedSource;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CiEnvironmentVariables {

  private static final Logger logger = LoggerFactory.getLogger(CiEnvironmentVariables.class);

  static final String CIVISIBILITY_REMOTE_ENV_VARS_PROVIDER_URL =
      "dd.civisibility.remote.env.vars.provider.url";
  static final String CIVISIBILITY_REMOTE_ENV_VARS_PROVIDER_KEY =
      "dd.civisibility.remote.env.vars.provider.key";

  static final String DD_ENV_VARS_PROVIDER_KEY_HEADER = "DD-Env-Vars-Provider-Key";

  private static final int CONNECT_TIMEOUT_MILLIS = 5000;
  private static final int READ_TIMEOUT_MILLIS = 10000;

  private static final Map<String, String> REMOTE_ENVIRONMENT;

  static {
    String url = getConfigValue(CIVISIBILITY_REMOTE_ENV_VARS_PROVIDER_URL);
    String key = getConfigValue(CIVISIBILITY_REMOTE_ENV_VARS_PROVIDER_KEY);
    if (url != null && key != null) {
      REMOTE_ENVIRONMENT = doWithBackoffRetries(() -> getRemoteEnvironment(url, key), null);
    } else {
      REMOTE_ENVIRONMENT = null;
    }
  }

  private static String getConfigValue(String propertyName) {
    String propertyValue = System.getProperty(propertyName);
    if (propertyValue != null) {
      return propertyValue;
    }
    return EnvironmentVariables.get(toEnvVar(propertyName));
  }

  private static String toEnvVar(String string) {
    return string.replace('.', '_').replace('-', '_').toUpperCase();
  }

  private static <T> T doWithBackoffRetries(Callable<T> action, T fallbackValue) {
    long delayMillis = 500;
    for (int i = 0; i < 5; i++) {
      if (Thread.currentThread().isInterrupted()) {
        logger.warn("Interrupted while trying to read remote environment");
        return fallbackValue;
      }
      try {
        return action.call();

      } catch (Exception e) {
        logger.warn("Error while trying to read remote environment", e);
        sleep(delayMillis);
        delayMillis *= 2;
      }
    }
    return fallbackValue;
  }

  private static void sleep(long delayMillis) {
    try {
      Thread.sleep(delayMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static Map<String, String> getRemoteEnvironment(String url, String key) throws Exception {
    HttpURLConnection conn = null;
    try {
      URL target = new URL(url);
      conn = (HttpURLConnection) target.openConnection();
      conn.setRequestMethod("GET");
      conn.setRequestProperty(DD_ENV_VARS_PROVIDER_KEY_HEADER, key);
      conn.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
      conn.setReadTimeout(READ_TIMEOUT_MILLIS);

      int code = conn.getResponseCode();
      if (code >= 200 && code < 300) {
        Moshi moshi = new Moshi.Builder().build();
        Type type = Types.newParameterizedType(Map.class, String.class, String.class);
        JsonAdapter<Map<String, String>> adapter = moshi.adapter(type);
        try (BufferedSource source = Okio.buffer(Okio.source(conn.getInputStream()))) {
          return adapter.fromJson(source);
        }

      } else {
        try (BufferedReader r =
            new BufferedReader(
                new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
          String body = r.lines().collect(Collectors.joining("\n"));
          throw new IOException(
              String.format("Remote environment request failed (HTTP %d) %s", code, body));
        }
      }

    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  @Nullable
  public static String get(String name) {
    if (REMOTE_ENVIRONMENT == null) {
      return null;
    }
    return REMOTE_ENVIRONMENT.get(name);
  }

  @Nullable
  public static Map<String, String> getAll() {
    return REMOTE_ENVIRONMENT != null ? Collections.unmodifiableMap(REMOTE_ENVIRONMENT) : null;
  }
}
