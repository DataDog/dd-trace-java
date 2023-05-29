package datadog.smoketest.springboot.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.UrlBasedViewResolver;

@Controller
public class IastViewController {

  @GetMapping("/unvalidated_redirect_from_string")
  public String unvalidatedRedirectFromString(@RequestParam String param) {
    return UrlBasedViewResolver.REDIRECT_URL_PREFIX + param;
  }

  @GetMapping("/unvalidated_redirect_forward_from_string")
  public String unvalidatedRedirectForwardFromString(@RequestParam String param) {
    return UrlBasedViewResolver.FORWARD_URL_PREFIX + param;
  }

  @GetMapping("/navigation_from_string")
  public String fromString(@RequestParam String param) {
    return param;
  }
}
