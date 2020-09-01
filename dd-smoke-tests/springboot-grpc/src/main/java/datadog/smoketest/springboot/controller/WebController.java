package datadog.smoketest.springboot.controller;

import datadog.smoketest.springboot.grpc.AsynchronousGreeter;
import datadog.smoketest.springboot.grpc.LocalInterface;
import datadog.smoketest.springboot.grpc.SynchronousGreeter;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class WebController implements AutoCloseable {

  private final AsynchronousGreeter asyncGreeter;
  private final SynchronousGreeter greeter;
  private final LocalInterface localInterface;

  public WebController() throws IOException {
    this.localInterface = new LocalInterface();
    this.asyncGreeter = new AsynchronousGreeter(localInterface.getPort());
    this.greeter = new SynchronousGreeter(localInterface.getPort());
  }

  @RequestMapping("/greeting")
  public String greeting() {
    return greeter.greet();
  }

  @RequestMapping("/async_greeting")
  public String asyncGreeting() {
    return asyncGreeter.greet();
  }

  @Override
  public void close() {
    localInterface.close();
    greeter.close();
    asyncGreeter.close();
  }
}
