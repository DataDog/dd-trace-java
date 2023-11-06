package datadog.smoketest.appsec.springtomcat7;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

public class Main {

  private static final String ROOT = "/";
  private static final String SERVLET = "dispatcherServlet";

  @SuppressForbidden
  public static void main(String[] args) throws Exception {
    int port = 8080;
    for (String arg : args) {
      if (arg.contains("=")) {
        String[] kv = arg.split("=");
        if (kv.length == 2) {
          if ("--server.port".equalsIgnoreCase(kv[0])) {
            try {
              port = Integer.parseInt(kv[1]);
            } catch (NumberFormatException e) {
              System.out.println(
                  "--server.port '"
                      + kv[1]
                      + "' is not valid port. Will be used default port "
                      + port);
            }
          }
        }
      }
    }

    Tomcat tomcat = new Tomcat();
    tomcat.setPort(port);

    Context context = tomcat.addContext(ROOT, new File(".").getAbsolutePath());

    Tomcat.addServlet(
        context,
        SERVLET,
        new DispatcherServlet(
            new AnnotationConfigWebApplicationContext() {
              {
                register(AppConfigurer.class);
              }
            }));
    context.addServletMapping(ROOT, SERVLET);

    tomcat.start();
    tomcat.getServer().await();
  }
}
