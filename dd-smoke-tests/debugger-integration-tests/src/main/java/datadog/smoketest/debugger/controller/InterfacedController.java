package datadog.smoketest.debugger.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InterfacedController implements InterfaceApi {

  @Override
  public String process() {
    return "OK";
  }
}

interface InterfaceApi {
  @RequestMapping("/process")
  String process();
}
