package io.sqreen.testapp.sampleapp

import io.sqreen.agent.sdk.Sqreen
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.event.AbstractAuthenticationEvent
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder
import org.springframework.security.config.annotation.authentication.configurers.provisioning.UserDetailsManagerConfigurer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.provisioning.UserDetailsManager
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler
import org.springframework.security.web.authentication.session.SessionFixationProtectionEvent
import org.springframework.security.web.authentication.switchuser.AuthenticationSwitchUserEvent
import org.springframework.web.context.support.ServletRequestHandledEvent

import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@Configuration
@EnableWebSecurity
class WebSecurityConfig extends WebSecurityConfigurerAdapter {
  @Override
  protected void configure(HttpSecurity http) {
    http
      .csrf().disable()
      .authorizeRequests()
      .antMatchers('/hello/secret').authenticated()
      .anyRequest().permitAll()
      .and()
      .formLogin()
      .loginPage("/login/")
      .failureHandler(new ForInteTestsAuthenticationFailureHandler())
      .permitAll()
      .and()
      .logout()
      .permitAll()
      .and()
      .httpBasic()
  }

  @Bean
  UserDetailsManager userDetailsManager(AuthenticationManagerBuilder auth) {
    def configurer = new  UserDetailsManagerConfigurer(
      new UserDetailsManagerWrapper(new InMemoryUserDetailsManager(new ArrayList<UserDetails>())))
    configurer.withUser('admin').password('admin123').roles('USER')
    configurer.configure(auth)
    configurer.userDetailsService
  }


  @Bean
  ApplicationListener<AbstractAuthenticationEvent> authenticationEventApplicationListener() {
    new ApplicationListener<AbstractAuthenticationEvent>() {
        @Override
        void onApplicationEvent(AbstractAuthenticationEvent event) {
          if (event instanceof SessionFixationProtectionEvent || event instanceof AuthenticationSwitchUserEvent
          || event instanceof AuthenticationSuccessEvent) {
            return
          }

          boolean success = event instanceof InteractiveAuthenticationSuccessEvent

          Sqreen.user()
            .authKey('username', event.authentication.name)
            .trackLogin(success)
        }
      }
  }

  @Bean
  ApplicationListener<ServletRequestHandledEvent> servletRequestHandledEventApplicationListener() {
    new ApplicationListener<ServletRequestHandledEvent>() {
        @Override
        void onApplicationEvent(ServletRequestHandledEvent event) {
          Authentication authentication = SecurityContextHolder.context.authentication
          if (!authentication || authentication instanceof AnonymousAuthenticationToken) {
            return
          }

          Sqreen.identify(username: authentication.name)
        }
      }
  }

  private static class UserDetailsManagerWrapper implements UserDetailsManager {
    UserDetailsManagerWrapper(UserDetailsManager delegate) {
      this.delegate = delegate
    }

    @Delegate
    final UserDetailsManager delegate

    @Override
    void createUser(UserDetails user) {
      delegate.createUser(user)
      if (user.username != 'myuser') {
        // ignore default user
        Sqreen.signupTrack(username: user.username)
      }
    }
  }

  private static class ForInteTestsAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private AuthenticationFailureHandler delegate = new SimpleUrlAuthenticationFailureHandler('/login/?error')

    @Override
    void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
      AuthenticationException exception) throws IOException, ServletException {
      if (IntegrationTestHelpers.isInteTestRequest(request)) {
        response.sendError(401)
        return
      }

      delegate.onAuthenticationFailure(request, response, exception)
    }
  }
}
