package datadog.smoketest.springboot.jwt;

import java.util.Arrays;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

public class JwtUserService implements UserDetailsService {
  public static final String USER = "USER";
  public static final String ROLE = "ROLE_USER";

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    return new User(
        username,
        "",
        Arrays.asList(new SimpleGrantedAuthority(USER), new SimpleGrantedAuthority(ROLE)));
  }
}
