import datadog.trace.agent.test.AgentTestRunner
import dd.trace.instrumentation.springsecurity.AuthenticatingLdapApplication
import dd.trace.instrumentation.springsecurity.WebSecurityConfig
import io.opentracing.tag.Tags
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDecisionManager
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.ConfigAttribute
import org.springframework.security.access.SecurityConfig
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import spock.lang.Shared

// we oot/docs/current/api/org/springframework/boot/test/context/SpringBootTest.html
////https://www.baeldung.com/springbootconfiguration-annotation
////As a result, the @SpringBootConfiguration annotation is the primary source for configuration in applications. Generally, the class that defines the main() method is a good candidate for this annotation.
////The recommendation is to only have one @SpringBootConfigurationneed to start the applictaion with the javagent, do the request ( post login, get page) and check the generated spans
//https://docs.spring.io/spring-b or @SpringBootApplication for your application. Most applications will simply use @SpringBootApplication.

/*
 -b, --cookie STRING/FILE  Read cookies from STRING/FILE (H)
 -c, --cookie-jar FILE  Write cookies to FILE after operation (H)
 -L, --location      Follow redirects (H)



  curl -c youhou http://localhost:8080/login
  curl -d 'username=bob&password=azerty' -b youhou -L http://localhost:8080/login

  client(true)=>  "-L"

   */


//@Import(WebSecurityConfig)
@SpringBootTest( webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,classes = AuthenticatingLdapApplication)
class SpringSecurityTest extends AgentTestRunner {

   @Shared
  protected AccessDecisionManager accessManager

  @Shared
  protected AuthenticationManager authManager


  def setupSpec() {

  def context = new AnnotationConfigApplicationContext()
  context.register(WebSecurityConfig)
  context.refresh()
    authManager= context.getBean(AuthenticationManager)
    accessManager = context.getBean(AccessDecisionManager)

  }



  //tests for access

def "access decision first test"() {

    setup:


    def grantedAuthorityList = new ArrayList<GrantedAuthority>()
    grantedAuthorityList.add(new GrantedAuthority() {
      @Override
      String getAuthority() {
        return "ROLE_DEVELOPERS"
      }
    })


//    UserDetails user = new User("bob","azerty", grantedAuthorityList);
//    UserDetails user = new User("bob","azerty", grantedAuthorityList);

    def request = new MockHttpServletRequest('GET', "http://localhost:8080/hello")
    def filterInvocation = new org.springframework.security.web.FilterInvocation(request, new MockHttpServletResponse(), new MockFilterChain())



    when:

    Authentication query = new UsernamePasswordAuthenticationToken("bob","azerty", grantedAuthorityList  )

    Authentication response = authManager.authenticate(query)


    List list =  new ArrayList<ConfigAttribute>()

    ConfigAttribute ca = new SecurityConfig("ROLE_YOUHOU")
    list.add(ca)

    accessManager.decide(response, filterInvocation, list)



    then:
    thrown(AccessDeniedException)

    assertTraces(2) {
      trace(0, 1) {
        span(0) {
          operationName "authentication"
          errored false
        }
      }
      trace(1, 1) {
        span(0) {
          operationName "access_decision"
          errored true
        }
      }
    }


  }

  def "access decision should succeed"() {

    setup:

    def grantedAuthorityList = new ArrayList<GrantedAuthority>()
    grantedAuthorityList.add(new GrantedAuthority() {
      @Override
      String getAuthority() {
        return "ROLE_DEVELOPERS"
      }
    })

    //UserDetails user = new User("bob","azerty", grantedAuthorityList);

    def request = new MockHttpServletRequest('GET', "http://localhost:8080/hello")
    def filterInvocation = new org.springframework.security.web.FilterInvocation(request, new MockHttpServletResponse(), new MockFilterChain())

    when:

    Authentication query = new UsernamePasswordAuthenticationToken("bob","azerty", grantedAuthorityList  )
    Authentication response = authManager.authenticate(query)
    List list =  new ArrayList<ConfigAttribute>()
    ConfigAttribute ca = new SecurityConfig("ROLE_DEVELOPERS")
    list.add(ca)

    accessManager.decide(response, filterInvocation, list)

    then:

    assertTraces(2) {
      trace(0, 1) {
        span(0) {
          operationName "authentication"
          errored false
        }
      }
      trace(1, 1) {
        span(0) {
          operationName "access_decision"
          errored false
        }
      }

    }


  }


  //tests for authentication

  def "Login success"() {

    setup:
    Authentication upat = new UsernamePasswordAuthenticationToken("bob","azerty", new ArrayList<GrantedAuthority>() )

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
            "authentication.authority" "ROLE_DEVELOPERS"
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
              defaultTags()
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




