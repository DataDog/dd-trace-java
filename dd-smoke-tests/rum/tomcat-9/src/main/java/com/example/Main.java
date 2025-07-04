package com.example;

import java.io.File;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;

public class Main {
  public static void main(String[] args) throws LifecycleException {
    int port = 8080;
    if (args.length == 1) {
      port = Integer.parseInt(args[0]);
    }

    Tomcat tomcat = new Tomcat();
    tomcat.setPort(port);
    tomcat.getConnector(); // This is required to make Tomcat start
    tomcat.setBaseDir(".");

    // Add webapp context
    String contextPath = "";
    String docBase = new File(".").getAbsolutePath();
    Context context = tomcat.addContext(contextPath, docBase);

    // Add servlet programmatically
    context.addServletContainerInitializer(
        (c, ctx) -> {
          ctx.addServlet("htmlServlet", new HtmlServlet()).addMapping("/html");
          ctx.addServlet("xmlServlet", new HtmlServlet()).addMapping("/xml");
        },
        null);

    tomcat.start();
    tomcat.getServer().await();
  }
}
