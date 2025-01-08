package com.example.hello;

import static com.example.Common.ENABLED;

import java.util.concurrent.CompletableFuture;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

  @RequestMapping("/hello")
  public String hello() {
    return "hello world";
  }

  @RequestMapping("/enableScheduling")
  public CompletableFuture<ResponseEntity<Void>> enableScheduling() {
    ENABLED.set(true);
    return CompletableFuture.supplyAsync(
        () -> {
          while (!ENABLED.get()) {
            try {
              Thread.sleep(200);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              break;
            }
          }
          return ResponseEntity.ok().build();
        });
  }
}
