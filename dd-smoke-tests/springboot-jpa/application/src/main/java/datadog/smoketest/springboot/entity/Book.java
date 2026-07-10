package datadog.smoketest.springboot.entity;

import java.util.List;
import javax.persistence.*;
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
public class Book {

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE)
  private int id;

  private String title;

  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
  @JoinColumn(name = "book_id")
  private List<Author> authors;

  @OneToOne(cascade = CascadeType.ALL, optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "owner_id")
  private Owner owner;

  private int updateCount;

  public void increaseUpdateCount() {
    this.updateCount++;
  }
}
