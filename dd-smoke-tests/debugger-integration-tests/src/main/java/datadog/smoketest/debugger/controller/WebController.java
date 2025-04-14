package datadog.smoketest.debugger.controller;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebController {
  @RequestMapping("/greeting")
  public String greeting() {
    processWithArg(42);
    return "Sup Dawg";
  }

  @SuppressForbidden
  private void processWithArg(int argInt) {
    System.out.println(argInt);
  }
}
