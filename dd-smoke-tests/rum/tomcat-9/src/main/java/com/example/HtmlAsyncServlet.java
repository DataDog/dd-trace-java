package com.example;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Timer;
import java.util.TimerTask;
import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class HtmlAsyncServlet extends HttpServlet {
  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    final AsyncContext asyncContext = req.startAsync();
    new Timer(false)
        .schedule(
            new TimerTask() {
              @Override
              public void run() {
                try {
                  asyncContext
                      .getResponse()
                      .getWriter()
                      .write(
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
                  asyncContext.complete();
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              }
            },
            2000);
  }
}
