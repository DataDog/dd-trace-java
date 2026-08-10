package datadog.smoketest.springboot.controller;

import bsh.Interpreter;
import bsh.Remote;
import java.io.StringReader;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CodeInjectionController {

  @GetMapping("/code_injection/beanshell")
  public String beanshell(final HttpServletRequest request) {
    final String param = request.getParameter("param");
    try {
      // The CODE_INJECTION sink fires on method entry, before the script is parsed/evaluated,
      // so an evaluation failure here does not affect the vulnerability being reported.
      new Interpreter().eval(param);
    } catch (final Exception e) {
      // ignore evaluation errors
    }
    return "ok";
  }

  @GetMapping("/code_injection/beanshell_reader")
  public String beanshellReader(final HttpServletRequest request) {
    final String param = request.getParameter("param");
    try {
      // Wrapping the tainted parameter in a StringReader propagates the taint to the reader
      // (StringReaderCallSite), so eval(Reader) exercises the reader sink path end-to-end.
      new Interpreter().eval(new StringReader(param));
    } catch (final Exception e) {
      // ignore evaluation errors
    }
    return "ok";
  }

  @GetMapping("/code_injection/beanshell_remote")
  public String beanshellRemote(final HttpServletRequest request) {
    final String url = request.getParameter("url");
    final String script = request.getParameter("script");
    try {
      // Remote.eval reports CODE_INJECTION on the script and SSRF on the url on method entry,
      // before it attempts the (here failing) connection.
      Remote.eval(url, script);
    } catch (final Exception e) {
      // ignore evaluation / connection errors
    }
    return "ok";
  }
}
