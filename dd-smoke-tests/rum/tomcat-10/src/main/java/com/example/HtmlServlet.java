package com.example;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

public class HtmlServlet extends HttpServlet {
  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    final PrintWriter writer = resp.getWriter();
    resp.setContentType("text/html;charset=UTF-8");
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
