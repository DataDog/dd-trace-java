package datadog.trace.instrumentation.springsecurity5

import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.provisioning.JdbcUserDetailsManager
import org.springframework.security.provisioning.UserDetailsManager
import org.springframework.security.web.SecurityFilterChain

import javax.sql.DataSource

@Configuration
@EnableWebSecurity
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
    (requests) -> requests
    .requestMatchers("/", "/success", "/register", "/login").permitAll()
    .anyRequest().authenticated())
    .csrf().disable()
    .formLogin((form) -> form.loginPage("/login").permitAll())
    return http.build()
  }

  @Bean
  DataSource getDataSource() {
    DataSourceBuilder dataSourceBuilder = DataSourceBuilder.create()
    dataSourceBuilder.driverClassName("org.h2.Driver")
    dataSourceBuilder.url("jdbc:h2:mem:authDB;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;INIT=RUNSCRIPT FROM 'classpath:schema.sql';")
    dataSourceBuilder.username("SA")
    dataSourceBuilder.password("")
    return dataSourceBuilder.build()
  }

  @Bean
  UserDetailsManager userDetailsService() {
    return new JdbcUserDetailsManager(dataSource)
  }
}