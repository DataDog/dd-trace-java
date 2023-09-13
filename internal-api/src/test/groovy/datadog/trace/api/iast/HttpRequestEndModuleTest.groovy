package datadog.trace.api.iast


import spock.lang.Specification

class HttpRequestEndModuleTest  extends Specification{
  def 'test isHtmlResponse'(){
    setup:
    final module = new HttpReqquestEndModuleTestImpl()

    when:
    final result = module.isHtmlResponse(htmlValue)

    then:
    result == expected

    where:
    htmlValue               | expected
    "text/html"             | true
    "application/xhtml+xml" | true
    "somethingelse"         | false
    null                    | false
    ""                      | false
  }

  def 'test isIgnorableResponseCode'(){
    setup:
    final module = new HttpReqquestEndModuleTestImpl()

    when:
    final result = module.isIgnorableResponseCode(code)

    then:
    result == expected

    where:
    code                                        | expected
    HttpURLConnection.HTTP_MOVED_PERM           | true
    HttpURLConnection.HTTP_MOVED_TEMP           | true
    HttpURLConnection.HTTP_NOT_MODIFIED         | true
    HttpURLConnection.HTTP_NOT_FOUND            | true
    HttpURLConnection.HTTP_GONE                 | true
    HttpURLConnection.HTTP_INTERNAL_ERROR       | true
    307i                                        | true
    200i                                        | false
    0i                                          | false
  }
}
