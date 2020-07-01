import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.Tags
import dd.trace.instrumentation.springsecurity.AuthenticatingLdapApplication
import dd.trace.instrumentation.springsecurity.WebSecurityConfig
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, classes = AuthenticatingLdapApplication)
class SpringSecurityTest extends AgentTestRunner {

  @Shared
  protected AccessDecisionManager accessManager

  @Shared
  protected AuthenticationManager authManager


  def setupSpec() {
    def context = new AnnotationConfigApplicationContext()
    context.register(WebSecurityConfig)
    context.refresh()
    authManager = context.getBean(AuthenticationManager)
    accessManager = context.getBean(AccessDecisionManager)
  }

  def "access decision test one"() {
    setup:
    def grantedAuthorityList = new ArrayList<GrantedAuthority>()
    grantedAuthorityList.add(new GrantedAuthority() {
      @Override
      String getAuthority() {
        return "ROLE_DEVELOPERS"
      }
    })

    def request = new MockHttpServletRequest('GET', "/hello")
    def filterInvocation = new org.springframework.security.web.FilterInvocation(request, new MockHttpServletResponse(), new MockFilterChain())

    when:
    Authentication query = new UsernamePasswordAuthenticationToken("bob", "azerty", grantedAuthorityList)
    Authentication response = authManager.authenticate(query)
    List list = new ArrayList<ConfigAttribute>()
    ConfigAttribute ca = new SecurityConfig("ROLE_YOUHOO")
    list.add(ca)
    accessManager.decide(response, filterInvocation, list)

    then:
    thrown(AccessDeniedException)

    assertTraces(2) {
      trace(0, 1) {
        span(0) {
          operationName "security.authenticate"
          resourceName "security.authenticate"
          errored false
          tags {
            "$Tags.COMPONENT" "spring-security"
            "auth.name" "bob"
            "auth.user.name" "bob"
            "auth.authenticated" true
            "auth.user.authorities" "ROLE_DEVELOPERS"
            "auth.user.non_expired" true
            "auth.user.account_non_locked" true
            "auth.user.credentials_non_locked" true
            defaultTags()
          }
        }
      }
      trace(1, 1) {
        span(0) {
          operationName "security.access_decision"
          resourceName "security.access_decision"
          errored true
          tags {
            "$Tags.COMPONENT" "spring-security"
            "auth.name" "bob"
            "auth.user.name" "bob"
            "auth.authenticated" true
            "auth.user.authorities" "ROLE_DEVELOPERS"
            "auth.user.non_expired" true
            "auth.user.account_non_locked" true
            "auth.user.credentials_non_locked" true
            "secured.object" "http://localhost/hello"
            "config.attribute" "ROLE_YOUHOO"
            errorTags(AccessDeniedException, "Access is denied")
            defaultTags()
          }
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

    def request = new MockHttpServletRequest('GET', "/hello")
    def filterInvocation = new org.springframework.security.web.FilterInvocation(request, new MockHttpServletResponse(), new MockFilterChain())

    when:
    Authentication query = new UsernamePasswordAuthenticationToken("bob", "azerty", grantedAuthorityList)
    Authentication response = authManager.authenticate(query)
    List list = new ArrayList<ConfigAttribute>()
    ConfigAttribute ca = new SecurityConfig("ROLE_DEVELOPERS")
    list.add(ca)

    accessManager.decide(response, filterInvocation, list)

    then:
    assertTraces(2) {
      trace(0, 1) {
        span(0) {
          operationName "security.authenticate"
          resourceName "security.authenticate"
          errored false
          tags {
            "$Tags.COMPONENT" "spring-security"
            "auth.name" "bob"
            "auth.user.name" "bob"
            "auth.authenticated" true
            "auth.user.authorities" "ROLE_DEVELOPERS"
            "auth.user.non_expired" true
            "auth.user.account_non_locked" true
            "auth.user.credentials_non_locked" true
            defaultTags()
          }
        }
      }
      trace(1, 1) {
        span(0) {
          operationName "security.access_decision"
          resourceName "security.access_decision"
          errored false
          tags {
            "$Tags.COMPONENT" "spring-security"
            "auth.name" "bob"
            "auth.user.name" "bob"
            "auth.authenticated" true
            "auth.user.authorities" "ROLE_DEVELOPERS"
            "auth.user.non_expired" true
            "auth.user.account_non_locked" true
            "auth.user.credentials_non_locked" true
            "secured.object" "http://localhost/hello"
            "config.attribute" "ROLE_DEVELOPERS"
            defaultTags()
          }
        }
      }
    }
  }


  //tests for authentication

  def "Login success"() {
    setup:
    Authentication upat = new UsernamePasswordAuthenticationToken("bob", "azerty", new ArrayList<GrantedAuthority>())

    when:
    def auth = authManager.authenticate(upat)

    then:
    auth != null
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          operationName "security.authenticate"
          resourceName "security.authenticate"
          errored false
          tags {
            "$Tags.COMPONENT" "spring-security"
            "auth.name" "bob"
            "auth.user.name" "bob"
            "auth.authenticated" true
            "auth.user.authorities" "ROLE_DEVELOPERS"
            "auth.user.non_expired" true
            "auth.user.account_non_locked" true
            "auth.user.credentials_non_locked" true
            defaultTags()
          }
        }
      }

    }
  }

  def "wrong password"() {

    setup:
    Authentication upat = new UsernamePasswordAuthenticationToken("bob", "ytreza", new ArrayList<GrantedAuthority>())

    when:

    def auth = authManager.authenticate(upat)

    then:
    thrown(BadCredentialsException)
    auth == null
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          operationName "security.authenticate"
          resourceName "security.authenticate"
          errored true
          tags {
            "$Tags.COMPONENT" "spring-security"
            "auth.name" "bob"
            "auth.authenticated" true
            errorTags(BadCredentialsException, "Bad credentials")
            defaultTags()
          }
        }
      }
    }
  }


  def "unknown login"() {

    setup:
    Authentication upat = new UsernamePasswordAuthenticationToken("toto", "ytreza", new ArrayList<GrantedAuthority>())

    when:

    def auth = authManager.authenticate(upat)

    then:
    thrown(BadCredentialsException)
    auth == null
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          operationName "security.authenticate"
          resourceName "security.authenticate"
          errored true
          tags {
            "$Tags.COMPONENT" "spring-security"
            "auth.name" "toto"
            "auth.authenticated" true
            errorTags(BadCredentialsException, "Bad credentials")
            defaultTags() //' 'language', 'runtime-id', 'thread.id', 'thread.name'
          }
        }
      }
    }
  }
}



