package spring

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition
import org.springframework.xml.xsd.SimpleXsdSchema

@SpringBootApplication
@Configuration
class EchoConfig {
  @Bean("echo-server")
  DefaultWsdl11Definition echo(SimpleXsdSchema echoXsd) {
    DefaultWsdl11Definition definition = new DefaultWsdl11Definition()
    definition.setPortTypeName("Echo")
    definition.setLocationUri("/echo-server/services")
    definition.setSchema(echoXsd)
    return definition
  }

  @Bean
  SimpleXsdSchema echoXsd() {
    return new SimpleXsdSchema(new ClassPathResource("echo.xsd"))
  }
}
