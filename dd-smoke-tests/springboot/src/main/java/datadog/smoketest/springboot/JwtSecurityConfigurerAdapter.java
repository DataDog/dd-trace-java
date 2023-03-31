package datadog.smoketest.springboot;

import jwt.JWTAuthenticationFilter;
import jwt.JwtAuthenticationProvider;
import jwt.JwtUserDetailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.SecurityContextPersistenceFilter;

/**
 * jwt security configurer adapter
 *
 * @author linux_china
 */
@Configuration
@EnableWebSecurity
public class JwtSecurityConfigurerAdapter extends WebSecurityConfigurerAdapter {
    /**
     * white urls for static resources
     */
    public String[] whiteUrls = new String[]{"/webjars/**","/css/**","/images/**","/js/**","/actuator/**"};

    protected void configure(HttpSecurity http) throws Exception {
        http.addFilterAfter(new JWTAuthenticationFilter(), SecurityContextPersistenceFilter.class)
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .authorizeRequests()
                .antMatchers(whiteUrls).permitAll()
                .anyRequest().authenticated()
                .and()
                .csrf().disable()
                .httpBasic().disable()
                .exceptionHandling()
                .authenticationEntryPoint((request, response, authException) -> {
                    response.sendError(401, "Access Denied: please add legal JWT token on Authorization(HTTP header). Detail: " + authException.getMessage() + " If you have problem, please contact linux_china");
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.sendError(403, "Access Denied: please check your authorities. If you have problem, please contact linux_china");
                });
    }


    @Autowired
    public void configureGlobal(AuthenticationManagerBuilder auth, AuthenticationProvider authenticationProvider, UserDetailsService userDetailsService) throws Exception {
        auth.authenticationProvider(authenticationProvider).userDetailsService(userDetailsService);
    }

    @Bean
    public AuthenticationProvider jwtAuthenticationProvider(UserDetailsService userDetailsService) throws Exception {
        return new JwtAuthenticationProvider(userDetailsService);
    }

    @Bean
    public UserDetailsService jwtUserDetailsService() {
        return new JwtUserDetailService();
    }

    @Override
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();
    }
}
