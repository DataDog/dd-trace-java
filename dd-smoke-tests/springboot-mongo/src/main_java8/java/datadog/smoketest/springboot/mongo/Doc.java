package datadog.smoketest.springboot.mongo;

import org.springframework.data.annotation.Id;

public class Doc {

  @Id public String id;

  public String name;

  public void setName(String name) {
    this.name = name;
  }

  public Doc(String name) {
    this.name = name;
  }
}
