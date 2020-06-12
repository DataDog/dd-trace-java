package test.boot

import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.config.annotation.web.configuration.AuthorizationServerConfigurerAdapter
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableAuthorizationServer

@Configuration
@EnableAuthorizationServer
class AuthServerConfig extends AuthorizationServerConfigurerAdapter {
}
