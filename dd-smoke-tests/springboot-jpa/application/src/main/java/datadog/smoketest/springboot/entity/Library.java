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
public class Library {

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE)
  private int id;

  private String name;

  @OneToMany(cascade = CascadeType.ALL)
  @JoinColumn(name = "library_id")
  private List<Book> books;

  private int updateCount;

  public void increaseUpdateCount() {
    this.updateCount++;
  }

  public boolean isIssueExists() {
    for (Book book : this.getBooks()) {
      if (book.getUpdateCount() != book.getOwner().getUpdateCount()) {
        return true;
      }
      for (Author author : book.getAuthors()) {
        if (book.getUpdateCount() != author.getUpdateCount()) {
          return true;
        }
      }
    }
    return false;
  }
}
