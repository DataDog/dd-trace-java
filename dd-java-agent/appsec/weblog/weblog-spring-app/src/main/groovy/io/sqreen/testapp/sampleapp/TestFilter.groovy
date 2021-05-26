package io.sqreen.testapp.sampleapp

import org.springframework.web.filter.GenericFilterBean

import javax.servlet.FilterChain
import javax.servlet.ServletException
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletResponse

class TestFilter extends GenericFilterBean {

  @Override
  void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
    HttpServletResponse resp = response

    resp.setHeader('Content-type', 'text/plain;charset=utf-8')
    resp.outputStream << 'This came from a filter\n'

    resp.outputStream << "encoding : "
    resp.outputStream << request.getCharacterEncoding()
    resp.outputStream << "\n"

    for (String s : request.getParameterNames()) {
      resp.outputStream << s + "=" + request.getParameter(s) + "\n"
    }

    resp.outputStream.close()
    // do not call anything further down the chain
  }
}
