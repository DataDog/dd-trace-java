package datadog.smoketest.springboot.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

@Controller
public class FormResponseController {
  private static final int RESPONSE_OFFSET = 6;

  private final SpringTemplateEngine templateEngine;

  public FormResponseController(SpringTemplateEngine templateEngine) {
    this.templateEngine = templateEngine;
  }

  @GetMapping("/form-response")
  public void formResponse(HttpServletResponse response) throws IOException {
    Context context = new Context(Locale.ROOT);
    context.setVariable("returnUrl", "https://app.example.test/flows/complete?request=request-123");
    context.setVariable("formAction", "https://provider.example.test/flows/continue");
    context.setVariable("requestId", "request-123");

    String content = templateEngine.process("form-response", context);
    // Exercise response writers that receive a slice of a reusable buffer.
    char[] responseBuffer = new char[RESPONSE_OFFSET + content.length()];
    content.getChars(0, content.length(), responseBuffer, RESPONSE_OFFSET);

    response.setContentType("text/html");
    response.getWriter().write(responseBuffer, RESPONSE_OFFSET, content.length());
  }
}
