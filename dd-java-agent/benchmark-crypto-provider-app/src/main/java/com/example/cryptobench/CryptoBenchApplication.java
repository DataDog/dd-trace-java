package com.example.cryptobench;

import com.example.cryptobench.shared.Accp;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CryptoBenchApplication {
  public static void main(String[] args) {
    if (Boolean.getBoolean("bench.install.accp")) {
      Accp.install();
    }
    SpringApplication.run(CryptoBenchApplication.class, args);
  }
}
