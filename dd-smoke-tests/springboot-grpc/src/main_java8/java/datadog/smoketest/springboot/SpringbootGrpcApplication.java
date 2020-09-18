package datadog.smoketest.springboot;

import datadog.smoketest.springboot.grpc.AsynchronousGreeter;
import datadog.smoketest.springboot.grpc.LocalInterface;
import datadog.smoketest.springboot.grpc.SynchronousGreeter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class SpringbootGrpcApplication {

  @Bean
  AsyncTask asyncTask(AsynchronousGreeter greeter) {
    return new AsyncTask(greeter);
  }

  @Bean
  AsynchronousGreeter asynchronousGreeter(LocalInterface localInterface) {
    return new AsynchronousGreeter(localInterface.getPort());
  }

  @Bean
  SynchronousGreeter synchronousGreeter(LocalInterface localInterface) {
    return new SynchronousGreeter(localInterface.getPort());
  }

  @Bean
  LocalInterface localInterface() throws IOException {
    return new LocalInterface();
  }

  public static void main(final String[] args) {
    ConfigurableApplicationContext app =
        SpringApplication.run(SpringbootGrpcApplication.class, args);
    Integer port = app.getBean("local.server.port", Integer.class);
    System.out.println(
        "Bound to " + port + " in " + ManagementFactory.getRuntimeMXBean().getUptime() + "ms");
  }
}
