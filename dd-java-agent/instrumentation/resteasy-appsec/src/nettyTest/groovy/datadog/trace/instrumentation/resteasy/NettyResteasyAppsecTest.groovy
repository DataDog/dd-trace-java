package datadog.trace.instrumentation.resteasy

import org.jboss.resteasy.plugins.server.netty.NettyJaxrsServer
import org.jboss.resteasy.spi.ResteasyDeployment
import spock.lang.Shared

class NettyResteasyAppsecTest extends AbstractResteasyAppsecTest {
  private final static int PORT = 59152

  @Shared
  NettyJaxrsServer netty

  @Override
  void startServer() {
    netty = new NettyJaxrsServer()
    ResteasyDeployment deployment
    def deploymentImplClass = ResteasyDeployment.interface ?
      'org.jboss.resteasy.core.ResteasyDeploymentImpl' :
      ResteasyDeployment.name

    deployment = Class.forName(deploymentImplClass).newInstance()
    deployment.application = new TestJaxRsApplication()

    netty.deployment = deployment
    netty.port = PORT
    netty.rootResourcePath = ''
    netty.securityDomain = null
    netty.start()
    address = URI.create("http://localhost:$PORT/")
  }

  @Override
  void stopServer() {
    netty.stop()
  }
}
