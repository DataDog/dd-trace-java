package jwt;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * jwt authentication provider
 *
 * @author linux_china
 */
public class JwtAuthenticationProvider implements AuthenticationProvider {
    private UserDetailsService userDetailsService;
    /**
     * JWT verifier
     */
    private JWTVerifier jwtVerifier;
    /**
     * secret, please replace it by your secret
     */
    private String secret = "secret";

    public JwtAuthenticationProvider(UserDetailsService userDetailsService) throws Exception {
        this.userDetailsService = userDetailsService;
        Algorithm algorithmHS = Algorithm.HMAC256(secret);
        jwtVerifier = JWT.require(algorithmHS).withIssuer("mvnsearch").build();
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        JwtAuthentication jwtAuthentication = (JwtAuthentication) authentication;
        try {
            DecodedJWT jwt = jwtVerifier.verify((String) jwtAuthentication.getCredentials());
            jwtAuthentication.setAuthenticated(true);
            jwtAuthentication.setJwtPayload(jwt);
            jwtAuthentication.setUserDetails(userDetailsService.loadUserByUsername(jwt.getSubject()));
        } catch (JWTVerificationException e) {
            jwtAuthentication.setAuthenticated(false);
            throw new BadCredentialsException("Your token is illegal.");
        }
        return jwtAuthentication;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return JwtAuthentication.class.isAssignableFrom(authentication);
    }
}
