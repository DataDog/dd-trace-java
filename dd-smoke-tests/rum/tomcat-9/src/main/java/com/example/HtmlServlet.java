package com.example;

import java.io.IOException;
import java.io.PrintWriter;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class HtmlServlet extends HttpServlet {
  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    resp.setContentType("text/html;charset=UTF-8");
    try (final PrintWriter writer = resp.getWriter()) {
      writer.write(
          "<!DOCTYPE html>"
              + "<html lang=\"en\">"
              + "<head>"
              + "    <meta charset=\"UTF-8\">"
              + "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
              + "    <title>Hello Servlet</title>"
              + "</head>"
              + "<body>"
              + "    <h1>Hello from Tomcat 9 Servlet!</h1>"
              + "    <p>This is a demo HTML page served by Java servlet.</p>"
              + "</body>"
              + "</html>");
    }
  }
}
