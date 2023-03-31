package jwt;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Arrays;

/**
 * user detail service
 *
 * @author linux_china
 */
public class JwtUserDetailService implements UserDetailsService {
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return new User(username, "", Arrays.asList(new SimpleGrantedAuthority("USER"), new SimpleGrantedAuthority("ROLE_USER")));
    }
}
