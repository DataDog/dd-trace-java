package com.datadog.iast.model

import datadog.trace.api.iast.model.SourceTypes
import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileDynamic

@CompileDynamic
class SourceTypeTest extends DDSpecification {

  void 'test toString'(final byte input, final String output) {
    when:
    final s = SourceType.toString(input)

    then:
    s == output

    where:
    input                              | output
    SourceType.NONE                    | null
    SourceType.REQUEST_PARAMETER_VALUE | SourceTypes.REQUEST_PARAMETER_VALUE
    SourceType.REQUEST_PARAMETER_NAME  | SourceTypes.REQUEST_PARAMETER_NAME
    SourceType.REQUEST_HEADER_VALUE    | SourceTypes.REQUEST_HEADER_VALUE
    SourceType.REQUEST_HEADER_NAME     | SourceTypes.REQUEST_HEADER_NAME
    SourceType.REQUEST_COOKIE_NAME     | SourceTypes.REQUEST_COOKIE_NAME
    SourceType.REQUEST_COOKIE_VALUE    | SourceTypes.REQUEST_COOKIE_VALUE
    SourceType.REQUEST_COOKIE_COMMENT  | SourceTypes.REQUEST_COOKIE_COMMENT
    SourceType.REQUEST_COOKIE_DOMAIN   | SourceTypes.REQUEST_COOKIE_DOMAIN
    SourceType.REQUEST_COOKIE_PATH     | SourceTypes.REQUEST_COOKIE_PATH
    SourceType.REQUEST_BODY            | SourceTypes.REQUEST_BODY
  }
}
