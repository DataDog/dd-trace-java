package com.example;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

import java.io.File;

public class Main {
  public static void main(String[] args) throws Exception {
    int port = 8080;
    if (args.length == 1) {
      port = Integer.parseInt(args[0]);
    }

    Tomcat tomcat = new Tomcat();
    tomcat.setPort(port);

    // Setup base directory
    tomcat.setBaseDir(".");

    // Add webapp context
    String contextPath = "/";
    String docBase = new File(".").getAbsolutePath();
    Context context = tomcat.addContext(contextPath, docBase);

    // Add servlet programmatically
    context.addServletContainerInitializer((c, ctx) -> {
      ctx.addServlet("helloServlet", new HelloServlet()).addMapping("/hello");
    }, null);

    tomcat.start();
    tomcat.getServer().await();
  }
}
