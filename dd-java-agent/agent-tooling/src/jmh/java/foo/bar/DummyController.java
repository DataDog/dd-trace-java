package foo.bar;

import javax.servlet.ServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DummyController {

  @GetMapping("/benchmark")
  public String index(final ServletRequest request) {
    return request.getParameter("param");
  }
}
