package datadog.trace.api.http

import datadog.trace.api.normalize.HttpResourceNames
import spock.lang.Specification

class HttpResourceNamesTest extends Specification {
  def "works as expected" () {
    when:
    def resourceName = HttpResourceNames.join(method, path)

    then:
    resourceName.toString() == expected

    where:
    method | path | expected
    "GET"  | "/test" | "GET /test"
    null   | "/test" | "/test"
    "GET"  | null    | "/"
  }
}
