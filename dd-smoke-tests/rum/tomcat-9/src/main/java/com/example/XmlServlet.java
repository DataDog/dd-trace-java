package com.example;

import java.io.IOException;
import java.io.PrintWriter;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class XmlServlet extends HttpServlet {
  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    resp.setContentType("application/xml;charset=UTF-8");
    try (PrintWriter out = resp.getWriter()) {
      out.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
      out.println("<response>");
      out.println("    <status>success</status>");
      out.println("    <message>RUM injection test</message>");
      out.println("    <data>");
      out.println("        <item id=\"1\">Test Item 1</item>");
      out.println("        <item id=\"2\">Test Item 2</item>");
      out.println("    </data>");
      out.println("</response>");
    }
  }
}
