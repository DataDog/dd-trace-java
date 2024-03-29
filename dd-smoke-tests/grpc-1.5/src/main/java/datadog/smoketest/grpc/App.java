/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package datadog.smoketest.grpc;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class App {

  private static final Logger logger = Logger.getLogger(App.class.getName());

  private Server server;

  private void start() throws IOException {
    int port = Integer.getInteger("grpc.http.port", 8080);
    server =
        Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
            .addService(new IastServiceImpl())
            .build()
            .start();
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  System.err.println("*** shutting down gRPC server since JVM is shutting down");
                  try {
                    App.this.stop();
                  } catch (InterruptedException e) {
                    e.printStackTrace(System.err);
                  }
                  System.err.println("*** server shut down");
                }));
  }

  private void stop() throws InterruptedException {
    if (server != null) {
      server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    final App app = new App();
    app.start();
    app.blockUntilShutdown();
  }
}
