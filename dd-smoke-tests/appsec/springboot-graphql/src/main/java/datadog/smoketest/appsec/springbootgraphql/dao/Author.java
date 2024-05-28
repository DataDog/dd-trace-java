package datadog.smoketest.appsec.springbootgraphql.dao;

import java.util.Arrays;
import java.util.List;

public class Author {

  private final String id;
  private final String firstName;
  private final String lastName;

  private Author(String id, String firstName, String lastName) {
    this.id = id;
    this.firstName = firstName;
    this.lastName = lastName;
  }

  public String getId() {
    return id;
  }

  public String getFirstName() {
    return firstName;
  }

  public String getLastName() {
    return lastName;
  }

  private static List<Author> authors =
      Arrays.asList(
          new Author("author-1", "Joshua", "Bloch"),
          new Author("author-2", "Douglas", "Adams"),
          new Author("author-3", "Bill", "Bryson"));

  public static Author getById(String id) {
    return authors.stream().filter(author -> author.id.equals(id)).findFirst().orElse(null);
  }
}
