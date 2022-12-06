package datadog.smoketest.springboot.controller;

import datadog.smoketest.springboot.rabbit.Receiver;
import datadog.smoketest.springboot.rabbit.Sender;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebController {
  private final Sender sender;
  private final Receiver receiver;

  public WebController(Sender sender, Receiver receiver) {
    this.sender = sender;
    this.receiver = receiver;
  }

  @RequestMapping("/roundtrip/{message}")
  public String roundtrip(@PathVariable String message) throws InterruptedException {
    sender.send(message);
    return "Got: " + receiver.poll(5000);
  }
}
