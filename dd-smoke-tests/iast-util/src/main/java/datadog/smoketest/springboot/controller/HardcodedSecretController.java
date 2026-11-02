// spotless:off - IAST smoke tests assert the secret's source line number.
package datadog.smoketest.springboot.controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HardcodedSecretController {

  @RequestMapping("/hardcodedSecret")
  public String hardcodedSecret() {
    return "AGE-SECRET-KEY-1QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ";
  }
}
// spotless:on
