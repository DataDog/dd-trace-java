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
    input                                   | output
    SourceTypes.NONE                        | null
    SourceTypes.REQUEST_PARAMETER_NAME      | 'http.request.parameter.name'
    SourceTypes.REQUEST_PARAMETER_VALUE     | 'http.request.parameter'
    SourceTypes.REQUEST_HEADER_NAME         | 'http.request.header.name'
    SourceTypes.REQUEST_HEADER_VALUE        | 'http.request.header'
    SourceTypes.REQUEST_COOKIE_NAME         | 'http.request.cookie.name'
    SourceTypes.REQUEST_COOKIE_VALUE        | 'http.request.cookie.value'
    SourceTypes.REQUEST_BODY                | 'http.request.body'
    SourceTypes.REQUEST_QUERY               | 'http.request.query'
    SourceTypes.REQUEST_PATH_PARAMETER      | 'http.request.path.parameter'
    SourceTypes.REQUEST_MATRIX_PARAMETER    | 'http.request.matrix.parameter'
    SourceTypes.REQUEST_MULTIPART_PARAMETER | 'http.request.multipart.parameter'
    SourceTypes.REQUEST_URI                 | 'http.request.uri'
    SourceTypes.REQUEST_PATH                | 'http.request.path'
    SourceTypes.GRPC_BODY                   | 'grpc.request.body'
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
