package datadog.smoketest.springboot.controller;

import datadog.smoketest.springboot.grpc.LocalInterface;
import datadog.smoketest.springboot.grpc.SynchronousGreeter;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class WebController implements AutoCloseable {

  private final SynchronousGreeter greeter;
  private final LocalInterface localInterface;

  public WebController() throws IOException {
    this.localInterface = new LocalInterface();
    this.greeter = new SynchronousGreeter(localInterface.getPort());
  }

  @RequestMapping("/greeting")
  public String greeting() {
    return greeter.greet();
  }

  @Override
  public void close() {
    localInterface.close();
    greeter.close();
  }
}
