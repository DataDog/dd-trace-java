import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.zuul.EnableZuulProxy;

@SpringBootApplication(scanBasePackages = "doesnotexist")
@EnableZuulProxy
public class ZuulGatewayTestApplication {}
