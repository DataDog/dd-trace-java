package smoketest.resteasy;

import io.undertow.Undertow;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.jboss.resteasy.spi.ResteasyDeployment;

public class Main {

  public static void main(String[] args) {
    int port = 8033;
    if (args.length == 1) {
      port = Integer.parseInt(args[0]);
      System.out.println("Starting smoketest resteasy server at port: " + port);
    }

    UndertowJaxrsServer server = new UndertowJaxrsServer();

    ResteasyDeployment deployment = new ResteasyDeployment();
    deployment.setApplicationClass(App.class.getName());
    deployment.setInjectorFactoryClass("org.jboss.resteasy.cdi.CdiInjectorFactory");

    DeploymentInfo deploymentInfo = server.undertowDeployment(deployment, "/");
    deploymentInfo.setClassLoader(Main.class.getClassLoader());
    deploymentInfo.setDeploymentName("Undertow + Resteasy example");
    deploymentInfo.setContextPath("/");

    deploymentInfo.addListener(
        Servlets.listener(org.jboss.weld.environment.servlet.Listener.class));

    server.deploy(deploymentInfo);

    Undertow.Builder builder = Undertow.builder().addHttpListener(port, "localhost");

    server.start(builder);
  }
}
