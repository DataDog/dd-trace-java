package datadog.trace.api.iast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class SourceTypesTest {

  @TableTest({
    "scenario                    | input                       | output                          ",
    "NONE                        | NONE                        |                                 ",
    "REQUEST_PARAMETER_NAME      | REQUEST_PARAMETER_NAME      | http.request.parameter.name     ",
    "REQUEST_PARAMETER_VALUE     | REQUEST_PARAMETER_VALUE     | http.request.parameter          ",
    "REQUEST_HEADER_NAME         | REQUEST_HEADER_NAME         | http.request.header.name        ",
    "REQUEST_HEADER_VALUE        | REQUEST_HEADER_VALUE        | http.request.header             ",
    "REQUEST_COOKIE_NAME         | REQUEST_COOKIE_NAME         | http.request.cookie.name        ",
    "REQUEST_COOKIE_VALUE        | REQUEST_COOKIE_VALUE        | http.request.cookie.value       ",
    "REQUEST_BODY                | REQUEST_BODY                | http.request.body               ",
    "REQUEST_QUERY               | REQUEST_QUERY               | http.request.query              ",
    "REQUEST_PATH_PARAMETER      | REQUEST_PATH_PARAMETER      | http.request.path.parameter     ",
    "REQUEST_MATRIX_PARAMETER    | REQUEST_MATRIX_PARAMETER    | http.request.matrix.parameter   ",
    "REQUEST_MULTIPART_PARAMETER | REQUEST_MULTIPART_PARAMETER | http.request.multipart.parameter",
    "REQUEST_URI                 | REQUEST_URI                 | http.request.uri                ",
    "REQUEST_PATH                | REQUEST_PATH                | http.request.path               ",
    "GRPC_BODY                   | GRPC_BODY                   | grpc.request.body               "
  })
  void testToString(@ConvertWith(IastConstantConverter.class) byte input, String output) {
    String result = SourceTypes.toString(input);

    assertEquals(output, result);
  }

  @TableTest({
    "scenario                | input                   | output                ",
    "NONE                    | NONE                    | NONE                  ",
    "REQUEST_PARAMETER_VALUE | REQUEST_PARAMETER_VALUE | REQUEST_PARAMETER_NAME",
    "REQUEST_PARAMETER_NAME  | REQUEST_PARAMETER_NAME  | REQUEST_PARAMETER_NAME",
    "REQUEST_HEADER_VALUE    | REQUEST_HEADER_VALUE    | REQUEST_HEADER_NAME   ",
    "REQUEST_HEADER_NAME     | REQUEST_HEADER_NAME     | REQUEST_HEADER_NAME   ",
    "REQUEST_COOKIE_NAME     | REQUEST_COOKIE_NAME     | REQUEST_COOKIE_NAME   ",
    "REQUEST_COOKIE_VALUE    | REQUEST_COOKIE_VALUE    | REQUEST_COOKIE_NAME   ",
    "REQUEST_BODY            | REQUEST_BODY            | REQUEST_BODY          ",
    "REQUEST_QUERY           | REQUEST_QUERY           | REQUEST_QUERY         "
  })
  void namedSources(
      @ConvertWith(IastConstantConverter.class) byte input,
      @ConvertWith(IastConstantConverter.class) byte output) {
    byte result = SourceTypes.namedSource(input);

    assertEquals(output, result);
  }
}
