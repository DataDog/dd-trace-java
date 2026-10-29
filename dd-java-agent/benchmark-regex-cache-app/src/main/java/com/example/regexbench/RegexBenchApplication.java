package com.example.regexbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Demo app with per-request regex anti-patterns, used to measure the regex cache POC. */
@SpringBootApplication
public class RegexBenchApplication {
  public static void main(String[] args) {
    SpringApplication.run(RegexBenchApplication.class, args);
  }
}
