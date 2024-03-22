package datadog.smoketest.appsec.springbootgraphql.dao;

import java.util.Arrays;
import java.util.List;

public class Book {

  private final String id;
  private final String name;
  private final int pageCount;
  private final String authorId;

  private Book(String id, String name, int pageCount, String authorId) {
    this.id = id;
    this.name = name;
    this.pageCount = pageCount;
    this.authorId = authorId;
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public int getPageCount() {
    return pageCount;
  }

  public String getAuthorId() {
    return authorId;
  }

  private static List<Book> books =
      Arrays.asList(
          new Book("book-1", "Effective Java", 416, "author-1"),
          new Book("book-2", "Hitchhiker's Guide to the Galaxy", 208, "author-2"),
          new Book("book-3", "Down Under", 436, "author-3"));

  public static Book getById(String id) {
    return books.stream().filter(book -> book.id.equals(id)).findFirst().orElse(null);
  }
}
