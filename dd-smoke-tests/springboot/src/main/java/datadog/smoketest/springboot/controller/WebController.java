package datadog.smoketest.springboot.controller;

import ddtest.client.sources.Hasher;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebController {
  @RequestMapping("/greeting")
  public String greeting() {
    return "Sup Dawg";
  }

  @RequestMapping("/weakhash")
  public String weakhash() {
    try {
      new Hasher().executeHash();
      return "MessageDigest.getInstance executed";
    } catch (Exception e) {
      return e.toString();
    }
  }
}
