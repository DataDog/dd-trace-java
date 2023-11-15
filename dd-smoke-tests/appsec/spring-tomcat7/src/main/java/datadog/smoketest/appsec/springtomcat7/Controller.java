package datadog.smoketest.appsec.springtomcat7;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Controller {

  @RequestMapping("/")
  public String htmlString() {
    return "Hello world!";
  }

  @RequestMapping("/exception")
  public void exceptionMethod() throws Throwable {
    throw new Throwable("hello");
  }
}
