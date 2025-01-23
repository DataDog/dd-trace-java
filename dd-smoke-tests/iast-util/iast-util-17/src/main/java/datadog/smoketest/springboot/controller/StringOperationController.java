package datadog.smoketest.springboot.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/string")
public class StringOperationController {

  @PostMapping("/translateEscapes")
  public String translateEscapes(@RequestParam(value = "parameter") final String parameter) {
    parameter.translateEscapes();
    return "ok";
  }
}
