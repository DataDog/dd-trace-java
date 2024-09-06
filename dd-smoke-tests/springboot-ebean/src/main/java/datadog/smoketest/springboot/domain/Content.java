package datadog.smoketest.springboot.domain;

import datadog.smoketest.springboot.domain.finder.ContentFinder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "exa_content")
public class Content extends BaseModel {

  public static final ContentFinder find = new ContentFinder();

  @Column(name = "name")
  String name;

  public Content() {}

  public Content(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
