package datadog.trace.api.iast

import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileDynamic

@CompileDynamic
class SourceTypesTest extends DDSpecification {

  void 'test toString'() {
    when:
    final s = SourceTypes.toString(input)

    then:
    s == output

    where:
    input                               | output
    SourceTypes.NONE                    | null
    SourceTypes.REQUEST_PARAMETER_VALUE | SourceTypes.REQUEST_PARAMETER_VALUE_STRING
    SourceTypes.REQUEST_PARAMETER_NAME  | SourceTypes.REQUEST_PARAMETER_NAME_STRING
    SourceTypes.REQUEST_HEADER_VALUE    | SourceTypes.REQUEST_HEADER_VALUE_STRING
    SourceTypes.REQUEST_HEADER_NAME     | SourceTypes.REQUEST_HEADER_NAME_STRING
    SourceTypes.REQUEST_COOKIE_NAME     | SourceTypes.REQUEST_COOKIE_NAME_STRING
    SourceTypes.REQUEST_COOKIE_VALUE    | SourceTypes.REQUEST_COOKIE_VALUE_STRING
    SourceTypes.REQUEST_BODY            | SourceTypes.REQUEST_BODY_STRING
    SourceTypes.REQUEST_QUERY           | SourceTypes.REQUEST_QUERY_STRING
  }

  void 'named sources'() {
    when:
    final s = SourceTypes.namedSource(input)

    then:
    s == output

    where:
    input                               | output
    SourceTypes.NONE                    | SourceTypes.NONE
    SourceTypes.REQUEST_PARAMETER_VALUE | SourceTypes.REQUEST_PARAMETER_NAME
    SourceTypes.REQUEST_PARAMETER_NAME  | SourceTypes.REQUEST_PARAMETER_NAME
    SourceTypes.REQUEST_HEADER_VALUE    | SourceTypes.REQUEST_HEADER_NAME
    SourceTypes.REQUEST_HEADER_NAME     | SourceTypes.REQUEST_HEADER_NAME
    SourceTypes.REQUEST_COOKIE_NAME     | SourceTypes.REQUEST_COOKIE_NAME
    SourceTypes.REQUEST_COOKIE_VALUE    | SourceTypes.REQUEST_COOKIE_NAME
    SourceTypes.REQUEST_BODY            | SourceTypes.REQUEST_BODY
    SourceTypes.REQUEST_QUERY           | SourceTypes.REQUEST_QUERY
  }
}
