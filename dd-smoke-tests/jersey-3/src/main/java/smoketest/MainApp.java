package smoketest;

import jakarta.ws.rs.ext.ParamConverter;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.internal.inject.ParamConverters.StringConstructor;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import smoketest.config.AutoScanFeature;

public class MainApp {
  public static final byte[] debugMarker = "debugmarker".getBytes();

  private static final Logger LOGGER = Logger.getLogger(MainApp.class.getName());

  // we start at port 8080
  public static final String BASE_URI = "http://localhost:";

  // Starts Grizzly HTTP server
  public static HttpServer startServer(String httpPort) {

    // scan packages
    final ResourceConfig config = new ResourceConfig();
    // config.packages(true, "com.mkyong");
    config.register(Resource.class);

    // enable auto scan @Contract and @Service
    config.register(AutoScanFeature.class);

    config.register(JacksonFeature.class);

    LOGGER.info("Starting Server........");

    URI uri = URI.create(BASE_URI + httpPort + "/");
    System.out.println("Starting server at URI: " + uri);
    final HttpServer httpServer = GrizzlyHttpServerFactory.createHttpServer(uri, config);

    return httpServer;
  }

  public static void main(String[] args) {
    String httpPort = "8034";
    ParamConverter paramConverter =
        new StringConstructor()
            .getConverter(String.class, new GenericClass(String.class).getMyType(), null);
    Object pepe = paramConverter.fromString("Pepe");

    if (args.length == 1) {
      httpPort = args[0];
    }
    try {

      final HttpServer httpServer = startServer(httpPort);

      // add jvm shutdown hook
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      System.out.println("Shutting down the application...");

                      httpServer.shutdownNow();

                      System.out.println("Done, exit.");
                    } catch (Exception e) {
                      Logger.getLogger(MainApp.class.getName()).log(Level.SEVERE, null, e);
                    }
                  }));

      System.out.println("Application started.");
      System.out.println("Stop the application using CTRL+C");

      // block and wait shut down signal, like CTRL+C
      Thread.currentThread().join();

    } catch (InterruptedException ex) {
      Logger.getLogger(MainApp.class.getName()).log(Level.SEVERE, null, ex);
    }
  }

  public static class GenericClass<T> {

    private final Class<T> type;

    public GenericClass(Class<T> type) {
      this.type = type;
    }

    public Class<T> getMyType() {
      return this.type;
    }
  }
}
