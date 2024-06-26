package datadog.smoketest.appsec.springboot.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;

@RestController
public class WebController {
  @RequestMapping("/greeting")
  public String greeting() {
    return "Sup AppSec Dawg";
  }

  @GetMapping("/id/{id}")
  public String pathParam(@PathVariable("id") String id) {
    return id;
  }

  static class BodyMappedClass {
    public String v;
  }

  @PostMapping("/request-body")
  public String requestBody(@RequestBody BodyMappedClass obj) {
    return obj.v;
  }

  @GetMapping("/sqli")
  public String sqli(@RequestParam("id") String id) throws Exception {
    Class.forName("org.h2.Driver");
    Connection conn = DriverManager.getConnection("jdbc:h2:mem:testdb", "sa", "");
    conn.prepareStatement("SELECT 1 FROM DUAL WHERE '1' = '" + id + "'").execute();
    return "EXECUTED";
  }
}
