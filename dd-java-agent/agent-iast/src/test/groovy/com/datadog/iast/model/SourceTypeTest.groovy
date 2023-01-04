package com.datadog.iast.model

import datadog.trace.test.util.DDSpecification

class SourceTypeTest extends DDSpecification {

  def 'test toString'(final byte input, final String output) {
    when:
    final s = SourceType.toString(input)

    then:
    s == output

    where:
    input                              | output
    SourceType.NONE                    | null
    SourceType.REQUEST_PARAMETER_VALUE | SourceType.REQUEST_PARAMETER_VALUE_STRING
    SourceType.REQUEST_PARAMETER_NAME  | SourceType.REQUEST_PARAMETER_NAME_STRING
    SourceType.REQUEST_HEADER_VALUE    | SourceType.REQUEST_HEADER_VALUE_STRING
    SourceType.REQUEST_HEADER_NAME     | SourceType.REQUEST_HEADER_NAME_STRING
    SourceType.REQUEST_COOKIE_NAME     | SourceType.REQUEST_COOKIE_NAME_STRING
    SourceType.REQUEST_COOKIE_VALUE    | SourceType.REQUEST_COOKIE_VALUE_STRING
    SourceType.REQUEST_COOKIE_COMMENT  | SourceType.REQUEST_COOKIE_COMMENT_STRING
    SourceType.REQUEST_COOKIE_DOMAIN   | SourceType.REQUEST_COOKIE_DOMAIN_STRING
    SourceType.REQUEST_COOKIE_PATH     | SourceType.REQUEST_COOKIE_PATH_STRING
  }
}
