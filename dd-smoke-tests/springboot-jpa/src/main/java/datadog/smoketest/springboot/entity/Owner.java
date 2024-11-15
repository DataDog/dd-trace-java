package datadog.smoketest.springboot.entity;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(of = "id")
public class Owner {

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE)
  private int id;

  private String name;

  private int updateCount;

  public void increaseUpdateCount() {
    this.updateCount++;
  }
}
