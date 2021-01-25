import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@SpringBootApplication(scanBasePackages = "doesnotexist")
public class TestApplication {

  @RequestMapping(value = "/available")
  public String available() {
    return "SUCCESS";
  }

  @RequestMapping(value = "/nested/{proxyPort}")
  public String nested(@PathVariable("proxyPort") String proxyPort) {
    final String uri = "http://localhost:" + proxyPort + "/test/available";
    RestTemplate restTemplate = new RestTemplate();
    return restTemplate.getForObject(uri, String.class);
  }
}
