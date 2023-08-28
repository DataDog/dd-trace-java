package datadog.smoketest.springboot.controller;

import java.io.PrintWriter;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.AbstractController;

public class SimpleIastController extends AbstractController {
  @Override
  protected ModelAndView handleRequestInternal(
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    Map<String, String> vars =
        (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    PrintWriter printWriter = response.getWriter();
    response.setHeader("Content-type", "text/plain");
    printWriter.write("Template variables:");
    printWriter.write(vars.toString());
    printWriter.write('\n');
    return null;
  }
}
