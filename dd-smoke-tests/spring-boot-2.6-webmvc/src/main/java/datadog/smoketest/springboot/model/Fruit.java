package datadog.smoketest.springboot.model;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table
public class Fruit {

  public Fruit() {}

  public Fruit(@Nonnull String name) {
    this.name = name;
  }

  @Id @GeneratedValue private Long id;

  @Column(nullable = false)
  private String name;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Fruit fruit = (Fruit) o;
    return Objects.equals(id, fruit.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}
