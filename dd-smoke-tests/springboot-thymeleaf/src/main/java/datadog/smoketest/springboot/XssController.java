package datadog.smoketest.springboot;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/xss")
public class XssController {

  @GetMapping("/utext")
  public String utext(@RequestParam(name = "string") String name, Model model) {
    model.addAttribute("xss", name);
    return "utext";
  }
}
