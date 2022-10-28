package com.datadog.iast.model

import datadog.trace.test.util.DDSpecification

class SourceTypeTest extends DDSpecification {

  def 'test toString'() {
    when:
    final s = SourceType.toString(input)

    then:
    s == output

    where:
    input                              | output
    SourceType.NONE                    | null
    SourceType.REQUEST_PARAMETER_VALUE | SourceType.REQUEST_PARAMETER_VALUE_STRING
    SourceType.REQUEST_PARAMETER_NAME  | SourceType.REQUEST_PARAMETER_NAME_STRING
    SourceType.REQUEST_PATH            | SourceType.REQUEST_PATH_STRING
    SourceType.REQUEST_QUERY_PARAMETER | SourceType.REQUEST_QUERY_PARAMETER_STRING
  }
}
