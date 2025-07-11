package datadog.trace.api.civisibility.git

import datadog.trace.api.git.PersonInfo
import spock.lang.Specification

class PersonInfoTest extends Specification {
  def "test isEmpty"() {
    expect:
    info.isEmpty() == empty

    where:
    info                                    | empty
    new PersonInfo(null, null, null)        | true
    new PersonInfo("", null, null)          | true
    new PersonInfo(null, "email", "date")   | false
    new PersonInfo("name", "email", "date") | false
  }

  def "test isComplete"() {
    expect:
    info.isComplete() == complete

    where:
    info                                    | complete
    new PersonInfo(null, null, null)        | false
    new PersonInfo("name", null, null)      | false
    new PersonInfo("", "", "")              | false
    new PersonInfo("name", "email", "date") | true
  }

  def "test info merge"() {
    expect:
    PersonInfo.merge(infoA, infoB) == result
    where:
    infoA                                      | infoB                                      | result
    new PersonInfo("nameA", "emailA", "dateA") | new PersonInfo("nameB", "emailB", "dateB") | new PersonInfo("nameA", "emailA", "dateA")
    new PersonInfo(null, null, null)           | new PersonInfo("nameB", "emailB", "dateB") | new PersonInfo("nameB", "emailB", "dateB")
    new PersonInfo("nameA", null, "dateA")     | new PersonInfo("nameB", "emailB", null)    | new PersonInfo("nameA", "emailB", "dateA")
    new PersonInfo("nameA", null, "dateA")     | new PersonInfo(null, null, null)           | new PersonInfo("nameA", null, "dateA")
  }
}
