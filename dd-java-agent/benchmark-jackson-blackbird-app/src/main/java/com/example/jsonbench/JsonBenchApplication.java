package com.example.jsonbench;

import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/** JSON-heavy demo app used to measure agent-driven Blackbird registration. */
@SpringBootApplication
public class JsonBenchApplication {
  public static void main(String[] args) {
    SpringApplication.run(JsonBenchApplication.class, args);
  }

  @Bean
  Jackson2ObjectMapperBuilderCustomizer manualBlackbird() {
    return builder -> {
      if (Boolean.getBoolean("bench.manual.blackbird")) {
        builder.postConfigurer(m -> m.registerModule(new BlackbirdModule()));
      }
    };
  }
}
