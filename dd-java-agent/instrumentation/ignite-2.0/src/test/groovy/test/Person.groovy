package test

import org.apache.ignite.cache.query.annotations.QuerySqlField
import org.apache.ignite.cache.query.annotations.QueryTextField

class Person implements Serializable {
  private static final long serialVersionUID = 1L

  Person() {
  }

  Person(String name, int age) {
    this.id = UUID.randomUUID()
    this.name = name
    this.age = age
  }

  @QuerySqlField(index = true)
  UUID id

  @QuerySqlField(index = true)
  @QueryTextField
  String name

  @QuerySqlField
  int age

  @Override
  String toString() {
    return new StringJoiner(", ", Person.getSimpleName() + "[", "]")
      .add("id=" + id)
      .add("name='" + name + "'")
      .add("age=" + age)
      .toString()
  }
}
