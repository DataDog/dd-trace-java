package test

import org.springframework.boot.context.embedded.EmbeddedServletContainer
import org.springframework.boot.context.embedded.jetty.JettyEmbeddedServletContainer
import org.springframework.boot.context.embedded.tomcat.TomcatEmbeddedServletContainer
import org.springframework.boot.context.embedded.undertow.UndertowEmbeddedServletContainer

enum ContainerType {
  TOMCAT("tomcat-server"),
  JETTY("jetty-server"),
  UNDERTOW("unknown"),
  DEFAULT("java-web-servlet")

  final String component

  ContainerType(String component) {
    this.component = component
  }

  static ContainerType forEmbeddedServletContainer(EmbeddedServletContainer container) {
    if (container instanceof TomcatEmbeddedServletContainer) {
      return TOMCAT
    } else if (container instanceof JettyEmbeddedServletContainer) {
      return JETTY
    } else if (container instanceof UndertowEmbeddedServletContainer) {
      return UNDERTOW
    }
    return DEFAULT
  }
}
