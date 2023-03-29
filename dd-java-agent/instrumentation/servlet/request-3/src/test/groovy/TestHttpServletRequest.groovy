import groovy.transform.CompileStatic

import javax.servlet.http.HttpServletRequest

@CompileStatic
class TestHttpServletRequest implements HttpServletRequest {
  @Delegate
  HttpServletRequest delegate
}
