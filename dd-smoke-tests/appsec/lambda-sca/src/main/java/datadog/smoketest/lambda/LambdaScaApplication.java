package datadog.smoketest.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.sun.net.httpserver.HttpServer;
import datadog.trace.api.GlobalTracer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Drives a real streaming Lambda handler and stubs the extension's invocation protocol. */
public final class LambdaScaApplication {
  public static void main(String[] args) throws Exception {
    HttpServer server =
        HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 0);
    // Invocation instrumentation calls the extension while the /invoke request is still running.
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/lambda/start-invocation",
        exchange -> {
          exchange.getResponseHeaders().add("x-datadog-trace-id", "123");
          exchange.getResponseHeaders().add("x-datadog-span-id", "456");
          exchange.getResponseHeaders().add("x-datadog-sampling-priority", "1");
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.createContext(
        "/lambda/end-invocation",
        exchange -> {
          System.out.println(
              "LAMBDA_END "
                  + exchange.getRequestHeaders().getFirst("lambda-runtime-aws-request-id"));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    // The setter belongs to the agent's isolated classloader, not the fixture's classpath.
    GlobalTracer.get()
        .getClass()
        .getClassLoader()
        .loadClass("datadog.trace.lambda.LambdaHandler")
        .getMethod("setExtensionBaseUrl", String.class)
        .invoke(null, "http://127.0.0.1:" + server.getAddress().getPort());
    // This endpoint only loads the class. The test waits for advisory telemetry before invoking it.
    server.createContext(
        "/load-dependency",
        exchange -> {
          try {
            Class.forName("com.github.junrar.LocalFolderExtractor");
            exchange.sendResponseHeaders(200, -1);
          } catch (ClassNotFoundException failure) {
            failure.printStackTrace();
            exchange.sendResponseHeaders(500, -1);
          } finally {
            exchange.close();
          }
        });
    LambdaScaHandler handler = new LambdaScaHandler();
    server.createContext(
        "/invoke/",
        exchange -> {
          String invocation = exchange.getRequestURI().getPath().substring("/invoke/".length());
          Context context =
              (Context)
                  Proxy.newProxyInstance(
                      Context.class.getClassLoader(),
                      new Class<?>[] {Context.class},
                      (proxy, method, arguments) -> {
                        switch (method.getName()) {
                          case "getAwsRequestId":
                            return invocation;
                          case "getRemainingTimeInMillis":
                            return 30_000;
                          case "getMemoryLimitInMB":
                            return 1024;
                          case "equals":
                            return proxy == arguments[0];
                          case "hashCode":
                            return System.identityHashCode(proxy);
                          case "toString":
                            return "LambdaContext[requestId=" + invocation + "]";
                          default:
                            return null;
                        }
                      });
          ByteArrayOutputStream output = new ByteArrayOutputStream();
          try {
            handler.handleRequest(
                new ByteArrayInputStream(invocation.getBytes(StandardCharsets.UTF_8)),
                output,
                context);
            byte[] response = output.toByteArray();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
          } catch (Exception failure) {
            failure.printStackTrace();
            exchange.sendResponseHeaders(500, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
  }
}
