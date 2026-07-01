package datadog.smoketest.kafka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableKafka
@EnableAsync
public class KafkaApplication {

  public static void main(final String[] args) {
    SpringApplication.run(KafkaApplication.class, args);
  }
}
