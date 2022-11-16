package datadog.smoketest.springboot.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.apache.catalina.servlet4preview.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IastWebController {
  @RequestMapping("/greeting")
  public String greeting() {
    return "Sup Dawg";
  }

  @RequestMapping("/weakhash")
  public String weakhash() {
    try {
      MessageDigest.getInstance("MD5").digest("Message body".getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      return "Error: " + e.toString();
    }
    return "Weak Hash page";
  }

  @RequestMapping("/getparameter")
  public String getParameter(@RequestParam String param, HttpServletRequest request) {
    // StringWriter sw = new StringWriter();
    // PrintWriter pw = new PrintWriter(sw);
    // new Throwable().printStackTrace(pw);
    // return sw.toString();
    // TestSuite testSuite = new TestSuite(new HttpServletRequestWrapper(request));
    // testSuite.getParameterMap();
    return "Param is: " + param;
  }
}
