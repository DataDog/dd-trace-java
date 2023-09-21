package datadog.smoketest.debugger;

import datadog.trace.api.Trace;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public class ServerDebuggerTestApplication {
  private static final MockResponse EMPTY_HTTP_200 = new MockResponse().setResponseCode(200);
  private static final String LOG_FILENAME = System.getenv().get("DD_LOG_FILE");
  private static final Map<String, Runnable> methodsByName = new HashMap<>();
  private final MockWebServer webServer = new MockWebServer();
  private final String controlServerUrl;
  private final OkHttpClient httpClient = new OkHttpClient();
  private String lastMatchedLine;

  public static void main(String[] args) throws Exception {
    System.out.println(ServerDebuggerTestApplication.class.getName());
    try {
      registerMethods();
      ServerDebuggerTestApplication serverTestApplication =
          new ServerDebuggerTestApplication(args[0]);
      serverTestApplication.start();
    } catch (IOException ex) {
      ex.printStackTrace();
    }
  }

  public static void registerMethods() {
    methodsByName.put("fullMethod", ServerDebuggerTestApplication::runFullMethod);
    methodsByName.put("tracedMethod", ServerDebuggerTestApplication::runTracedMethod);
  }

  public ServerDebuggerTestApplication(String controlServerUrl) {
    this.controlServerUrl = controlServerUrl;
  }

  public void start() throws Exception {
    webServer.setDispatcher(new AppDispatcher(this));
    webServer.start();
    HttpUrl appUrl = webServer.url("/app");
    // Ack app started
    System.out.println("Send ack with app URl: " + appUrl.toString());
    RequestBody body = RequestBody.create(MediaType.get("text/plain"), appUrl.toString());
    Request ackRequest = new Request.Builder().url(controlServerUrl).post(body).build();
    try (Response response = httpClient.newCall(ackRequest).execute()) {}

    System.out.println("Send Ack done");
  }

  protected void stop() {
    System.out.println("Stopping app...");
    new Thread(
            () -> {
              try {
                webServer.shutdown();
              } catch (IOException e) {
                e.printStackTrace();
              }
            })
        .start();
  }

  protected void waitForInstrumentation(String className) {
    System.out.println("waitForInstrumentation on " + className);
    try {
      lastMatchedLine =
          TestApplicationHelper.waitForInstrumentation(LOG_FILENAME, className, lastMatchedLine);
      System.out.println("instrumented!");
    } catch (IOException ex) {
      ex.printStackTrace();
    }
  }

  protected void waitForReTransformation(String className) {
    System.out.println("waitForReTransformation on " + className);
    try {
      lastMatchedLine =
          TestApplicationHelper.waitForReTransformation(LOG_FILENAME, className, lastMatchedLine);
      System.out.println("re-transformed!");
    } catch (IOException ex) {
      ex.printStackTrace();
    }
  }

  protected void execute(String methodName) {
    Runnable method = methodsByName.get(methodName);
    if (method == null) {
      throw new RuntimeException("cannot find method: " + methodName);
    }
    System.out.println("Executing method: " + methodName);
    method.run();
    System.out.println("Executed");
  }

  private static void runFullMethod() {
    Map<String, String> map = new HashMap<>();
    map.put("key1", "val1");
    map.put("key2", "val2");
    map.put("key3", "val3");
    fullMethod(42, "foobar", 3.42, map, "var1", "var2", "var3");
  }

  @Trace
  private static void runTracedMethod() {
    Map<String, String> map = new HashMap<>();
    map.put("key1", "val1");
    map.put("key2", "val2");
    map.put("key3", "val3");
    tracedMethod(42, "foobar", 3.42, map, "var1", "var2", "var3");
  }

  private static String fullMethod(
      int argInt, String argStr, double argDouble, Map<String, String> argMap, String... argVar) {
    try {
      return argInt
          + ", "
          + argStr
          + ", "
          + argDouble
          + ", "
          + argMap
          + ", "
          + String.join(",", argVar);
    } catch (Exception ex) {
      ex.printStackTrace();
      return null;
    }
  }

  private static String tracedMethod(
      int argInt, String argStr, double argDouble, Map<String, String> argMap, String... argVar) {
    try {
      return argInt
          + ", "
          + argStr
          + ", "
          + argDouble
          + ", "
          + argMap
          + ", "
          + String.join(",", argVar);
    } catch (Exception ex) {
      ex.printStackTrace();
      return null;
    }
  }

  private static class AppDispatcher extends Dispatcher {
    private final ServerDebuggerTestApplication app;

    public AppDispatcher(ServerDebuggerTestApplication app) {
      this.app = app;
    }

    @Override
    public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
      String path = request.getRequestUrl().url().getPath();
      switch (path) {
        case "/app/waitForInstrumentation":
          {
            String className = request.getRequestUrl().queryParameter("classname");
            app.waitForInstrumentation(className);
            break;
          }
        case "/app/waitForReTransformation":
          {
            String className = request.getRequestUrl().queryParameter("classname");
            app.waitForReTransformation(className);
            break;
          }
        case "/app/execute":
          {
            String methodName = request.getRequestUrl().queryParameter("methodname");
            app.execute(methodName);
            break;
          }
        case "/app/stop":
          {
            app.stop();
            break;
          }
        default:
          throw new IllegalArgumentException("Unsupported url path: " + path);
      }
      return EMPTY_HTTP_200;
    }
  }
}
