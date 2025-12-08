package datadog.smoketest.springboot.controller;

import datadog.trace.api.Trace;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebController {
  @RequestMapping("/hello")
  public String hello() {
    return doHello();
  }

  @Trace
  private String doHello() {
    return sayHello();
  }

  /** DD_TRACE_METHOD="datadog.smoketest.springboot.controller.WebController[sayHello]" */
  private String sayHello() {
    return "Hello world";
  }
}
