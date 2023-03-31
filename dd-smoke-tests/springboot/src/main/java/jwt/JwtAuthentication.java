package jwt;


import com.auth0.jwt.interfaces.Payload;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

/**
 * JWT Authentication
 *
 * @author linux_china
 */
public class JwtAuthentication implements Authentication {
    private String jwtToken;
    private boolean authenticated = false;
    private UserDetails userDetails;
    private Payload jwtPayload;

    JwtAuthentication(String jwtToken) {
        this.jwtToken = jwtToken;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return userDetails.getAuthorities();
    }

    @Override
    public Object getCredentials() {
        return jwtToken;
    }

    @Override
    public Object getDetails() {
        return this.userDetails;
    }

    /**
     * java security Principal
     *
     * @return java.security.Principal
     */
    @Override
    public Object getPrincipal() {
        return this.getName();
    }

    @Override
    public boolean isAuthenticated() {
        return this.authenticated;
    }

    @Override
    public void setAuthenticated(boolean authenticated) throws IllegalArgumentException {
        this.authenticated = authenticated;
    }

    @Override
    public String getName() {
        return jwtPayload.getSubject();
    }

    public void setUserDetails(UserDetails userDetails) {
        this.userDetails = userDetails;
    }

    public void setJwtPayload(Payload jwtPayload) {
        this.jwtPayload = jwtPayload;
        //todo implement some logic after verified
    }
}
