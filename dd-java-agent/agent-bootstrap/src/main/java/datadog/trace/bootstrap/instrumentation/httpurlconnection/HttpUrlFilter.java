package datadog.trace.bootstrap.instrumentation.httpurlconnection;

import java.net.HttpURLConnection;

public class HttpUrlFilter {

    public static String HTTP_TRACE_ENABLED_KEY = "x-datadog-tracing-enabled";

    public static boolean preventTracing(HttpURLConnection connection) {
      return connection.getURL().toString().startsWith("http://127.0.0.1:8124/lambda");
    }

}