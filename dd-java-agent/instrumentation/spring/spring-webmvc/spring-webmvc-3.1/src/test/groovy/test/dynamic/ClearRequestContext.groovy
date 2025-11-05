package test.dynamic

import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.filter.OncePerRequestFilter

import javax.servlet.FilterChain
import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class ClearRequestContext extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
    RequestContextHolder.resetRequestAttributes()
    filterChain.doFilter(request, response)
  }
}
