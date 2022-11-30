package com.openliberty.demo;

import java.security.MessageDigest;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@Slf4j
public class DemoController {
  private int[] users;
  private final int MAX_REQUESTS = 100000000;

  @Value("${server.port}")
  private String httpPort;

  public DemoController() {
    this.users = new int[100];
  }

  @RequestMapping("/connect")
  public String connect() {
    int userId = new Random().nextInt(users.length);
    log.info(String.format("Length:%d", this.users.length));
    log.info(String.format("Visiting user with userid:%d", userId));
    RestTemplate restTemplate = new RestTemplate();
    String uri = String.format("http://localhost:%s/connect/%d", this.httpPort, userId);

    log.info(String.format("uri:%s", uri));
    String result = restTemplate.getForObject(uri, String.class);
    return result;
  }

  @RequestMapping("/connect/{user}")
  public String connectFinal(@PathVariable("user") final int user) {
    int visitID = this.users[user]++ % MAX_REQUESTS;
    log.info("User {} visited {} times", user, visitID);
    return String.format("%d:%d", user, visitID);
  }

  @RequestMapping("/vulnerability")
  public String vulnerability() {
    try {
      MessageDigest.getInstance("MD5");
    } catch (Exception e) {
      return e.toString();
    }
    return "OK";
  }
}
