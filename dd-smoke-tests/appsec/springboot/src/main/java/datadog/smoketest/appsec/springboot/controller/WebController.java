package datadog.smoketest.appsec.springboot.controller;

import java.io.File;
import java.net.URL;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

  @GetMapping("/sqli/query")
  public String sqliQuery(@RequestParam("id") String id) throws Exception {
    Connection conn = DriverManager.getConnection("jdbc:h2:mem:testdb", "sa", "");
    conn.createStatement().execute("SELECT 1 FROM DUAL WHERE '1' = '" + id + "'");
    return "EXECUTED";
  }

  @GetMapping("/sqli/header")
  public String sqliHeader(@RequestHeader("x-custom-header") String id) throws Exception {
    Connection conn = DriverManager.getConnection("jdbc:h2:mem:testdb", "sa", "");
    conn.createStatement().execute("SELECT 1 FROM DUAL WHERE '1' = '" + id + "'");
    return "EXECUTED";
  }

  @GetMapping("/ssrf/query")
  public String ssrfQuery(@RequestParam("domain") String domain) {
    try {
      new URL("http://" + domain).openStream().close();
    } catch (Throwable e) {
      // ignore errors opening connection
    }
    return "EXECUTED";
  }

  @GetMapping("/lfi/file")
  public String lfiFile(@RequestParam("path") String path) {
    new File(path);
    return "EXECUTED";
  }

  @GetMapping("/lfi/paths")
  public String lfiPaths(@RequestParam("path") String path) {
    Paths.get(path);
    return "EXECUTED";
  }

  @GetMapping("/lfi/path")
  public String lfiPath(@RequestParam("path") String path) {
    new File(System.getProperty("user.dir")).toPath().resolve(path);
    return "EXECUTED";
  }

  @RequestMapping("/session")
  public ResponseEntity<String> session(final HttpServletRequest request) {
    final HttpSession session = request.getSession(true);
    return new ResponseEntity<>(session.getId(), HttpStatus.OK);
  }
}
