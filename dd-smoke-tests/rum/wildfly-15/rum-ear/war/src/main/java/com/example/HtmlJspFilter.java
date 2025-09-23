package com.example;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;

@WebFilter(filterName = "test", value = "/*", asyncSupported = true)
public class HtmlJspFilter implements Filter {
  @Override
  public void doFilter(
      ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
      throws IOException, ServletException {
    final String uri = ((HttpServletRequest) servletRequest).getRequestURI();
    if (uri.contains("/xml")) {
      filterChain.doFilter(servletRequest, servletResponse);
    } else {
      // FIXME: async injection looks not working on wildfly
      servletRequest.getRequestDispatcher("/jsp/html.jsp").forward(servletRequest, servletResponse);
    }
  }
}
