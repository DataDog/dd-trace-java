package datadog.smoketest.springboot;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ViewController {

  @GetMapping("/test_xss_in_jsp")
  public String test() {
    return "test_xss";
  }
}
