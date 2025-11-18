package datadog.trace.instrumentation.resteasy

import datadog.trace.agent.test.utils.PortUtils
import org.jboss.resteasy.plugins.server.netty.NettyJaxrsServer
import org.jboss.resteasy.spi.ResteasyDeployment
import spock.lang.Shared

class NettyResteasyAppsecTest extends AbstractResteasyAppsecTest {

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
    netty.port = PortUtils.randomOpenPort()
    netty.rootResourcePath = ''
    netty.securityDomain = null
    netty.start()
    address = URI.create("http://localhost:${netty.port}/")
  }

  @Override
  void stopServer() {
    netty.stop()
  }
}
