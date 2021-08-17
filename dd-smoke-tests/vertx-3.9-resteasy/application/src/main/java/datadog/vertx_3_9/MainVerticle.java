package datadog.vertx_3_9;

import io.vertx.core.VertxOptions;
import java.math.BigInteger;
import java.util.concurrent.ThreadLocalRandom;
import org.jboss.resteasy.plugins.server.vertx.VertxJaxrsServer;
import org.jboss.resteasy.plugins.server.vertx.VertxResteasyDeployment;

public class MainVerticle {

  public static void main(String[] args) {
    VertxOptions options = new VertxOptions();
    options.setEventLoopPoolSize(1);
    options.setWorkerPoolSize(2);
    options.setInternalBlockingPoolSize(1);

    VertxResteasyDeployment deployment = new VertxResteasyDeployment();
    deployment.start();
    deployment.getRegistry().addPerInstanceResource(ResteasyResource.class);

    VertxJaxrsServer server = new VertxJaxrsServer();
    server.setVertxOptions(options);
    server.setDeployment(deployment);
    server.setPort(Integer.getInteger("vertx.http.port", 8080));
    server.setRootResourcePath("");
    server.setSecurityDomain(null);
    server.start();
  }

  static BigInteger randomFactorial() {
    int n = ThreadLocalRandom.current().nextInt(5, 100);
    BigInteger factorial = BigInteger.ONE;
    for (int i = 1; i <= n; i++) {
      factorial = factorial.multiply(BigInteger.valueOf(i));
    }
    return factorial;
  }
}
