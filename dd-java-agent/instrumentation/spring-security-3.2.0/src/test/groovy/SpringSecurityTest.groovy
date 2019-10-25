import datadog.trace.agent.test.AgentTestRunner
import dd.trace.instrumentation.springsecurity.AuthenticatingLdapApplication
import dd.trace.instrumentation.springsecurity.WebSecurityConfig
import io.opentracing.tag.Tags
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
//import org.springframework.security.access.ConfigAttribute
//import org.springframework.security.access.AccessDecisionManager


import spock.lang.Shared

// we oot/docs/current/api/org/springframework/boot/test/context/SpringBootTest.html
////https://www.baeldung.com/springbootconfiguration-annotation
////As a result, the @SpringBootConfiguration annotation is the primary source for configuration in applications. Generally, the class that defines the main() method is a good candidate for this annotation.
////The recommendation is to only have one @SpringBootConfigurationneed to start the applictaion with the javagent, do the request ( post login, get page) and check the generated spans
//https://docs.spring.io/spring-b or @SpringBootApplication for your application. Most applications will simply use @SpringBootApplication.


@Import(WebSecurityConfig)
@SpringBootTest( webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,classes = AuthenticatingLdapApplication)
class SpringSecurityTest extends AgentTestRunner {



  @Shared
  protected AuthenticationManager authManager

  def setupSpec() {

  def context = new AnnotationConfigApplicationContext()
  context.register(WebSecurityConfig)
  context.refresh()
  authManager = context.getBean(AuthenticationManager)
  }



  def "Login success"() {

    setup:
    Authentication upat = new UsernamePasswordAuthenticationToken("bob","azerty", new ArrayList<GrantedAuthority>() )
    UserDetails user = new User("bob","azerty", new ArrayList<GrantedAuthority>());


    when:

    def auth = authManager.authenticate(upat)

    then:
    auth != null
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          operationName "authentication"
          errored false
          tags {
            "$Tags.COMPONENT.key" "spring-security"
            "authentication.principal.is_credentials_non_locked" true
            "authentication.principal" "bob"
            "authentication.authority.0" "ROLE_DEVELOPERS"
            "authentication.isAuthenticated" true
            "authentication.principal.is_account_non_expired" true
            "authentication.principal.is_account_non_locked" true
            "authentication.principal.username" "bob"
            defaultTags()
          }
        }
      }
    }

  }

  def "wrong password"() {

    setup:
    Authentication upat = new UsernamePasswordAuthenticationToken("bob","ytreza", new ArrayList<GrantedAuthority>() )

    when:

    def auth = authManager.authenticate(upat)

    then:
    thrown(BadCredentialsException)
    auth == null
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          operationName "authentication"
          errored true
          tags {
               "$Tags.COMPONENT.key" "spring-security"
              "authentication.principal" "bob"
              "error.type" "org.springframework.security.authentication.BadCredentialsException"
              "authentication.isAuthenticated" true
              "error" true
              "error.msg" "Bad credentials"
              "error.stack" String
              defaultTags() //' 'language', 'runtime-id', 'thread.id', 'thread.name'
         }
        }
      }
    }

  }
  def "unknown login"() {

    setup:
    Authentication upat = new UsernamePasswordAuthenticationToken("toto","ytreza", new ArrayList<GrantedAuthority>() )

    when:

    def auth = authManager.authenticate(upat)

    then:
    thrown(BadCredentialsException)
    auth == null
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          operationName "authentication"
          errored true
          tags {
            "$Tags.COMPONENT.key" "spring-security"
            "authentication.principal" "toto"
            "error.type" "org.springframework.security.authentication.BadCredentialsException"
            "authentication.isAuthenticated" true
            "error" true
            "error.msg" "Bad credentials"
            "error.stack" String
            defaultTags() //' 'language', 'runtime-id', 'thread.id', 'thread.name'
          }
        }
      }
    }

  }

}




