import java.util.Arrays;
import java.util.List;

public class Book {
  private String id;
  private String name;
  private int pageCount;
  private String authorId;
  private String isbn;

  public Book(String id, String name, int pageCount, String authorId, String isbn) {
    this.id = id;
    this.name = name;
    this.pageCount = pageCount;
    this.authorId = authorId;
    this.isbn = isbn;
  }

  private static List<Book> books =
      Arrays.asList(
          new Book("book-1", "Harry Potter and the Philosopher's Stone", 223, "author-1", "isbn1"),
          new Book("book-2", "Moby Dick", 635, "author-2", "isbn2"),
          new Book("book-3", "Interview with the vampire", 371, "author-3", "isbn3"));

  public static Book getById(String id) {
    return books.stream().filter(book -> book.getId().equals(id)).findFirst().orElse(null);
  }

  public String getId() {
    return id;
  }

  public String getAuthorId() {
    return authorId;
  }

  public String getIsbn() {
    return isbn;
  }
}
