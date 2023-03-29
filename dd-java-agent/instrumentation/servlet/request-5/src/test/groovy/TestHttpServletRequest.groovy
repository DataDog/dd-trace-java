import groovy.transform.CompileStatic

import jakarta.servlet.http.HttpServletRequest

@CompileStatic
class TestHttpServletRequest implements HttpServletRequest {
  @Delegate
  HttpServletRequest delegate
}
