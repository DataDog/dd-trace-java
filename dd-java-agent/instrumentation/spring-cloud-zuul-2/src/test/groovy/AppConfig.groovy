import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.cloud.netflix.zuul.EnableZuulProxy

// Component scan defeats the purpose of configuring with specific classes
@SpringBootApplication(scanBasePackages = "doesnotexist")
@EnableZuulProxy
class AppConfig {}
