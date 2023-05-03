package datadog.smoketest.model

import groovy.transform.CompileStatic
import groovy.transform.ToString

@CompileStatic
@ToString
class TaintedObject {

  String value
  List<Range> ranges

  @ToString
  class Range {
    int start
    int end
    Source source
  }

  @ToString
  class Source {
    String name
    String value
    String origin
  }
}
