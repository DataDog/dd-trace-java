package datadog.smoketest.springboot.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.Locale;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/xss")
public class XssController {

  @GetMapping("/write")
  @SuppressFBWarnings
  public void write(final HttpServletRequest request, final HttpServletResponse response) {
    try {
      response.getWriter().write(request.getParameter("string"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @GetMapping("/write2")
  @SuppressFBWarnings
  public void write2(final HttpServletRequest request, final HttpServletResponse response) {
    try {
      response.getWriter().write(request.getParameter("string").toCharArray());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @GetMapping("/write3")
  @SuppressFBWarnings
  public void write3(final HttpServletRequest request, final HttpServletResponse response) {
    try {
      String insecure = request.getParameter("string");
      response.getWriter().write(insecure, 0, insecure.length());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @GetMapping("/write4")
  @SuppressFBWarnings
  public void write4(final HttpServletRequest request, final HttpServletResponse response) {
    try {
      char[] buf = request.getParameter("string").toCharArray();
      response.getWriter().write(buf, 0, buf.length);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @GetMapping("/print")
  @SuppressFBWarnings
  public void print(final HttpServletRequest request, final HttpServletResponse response) {
    try {
      response.getWriter().print(request.getParameter("string"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @GetMapping("/print2")
  @SuppressFBWarnings
  public void print2(final HttpServletRequest request, final HttpServletResponse response) {
    try {
      response.getWriter().print(request.getParameter("string").toCharArray());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @GetMapping("/println")
  @SuppressFBWarnings
  public void println(final HttpServletRequest request, final HttpServletResponse response) {
    try {
      response.getWriter().println(request.getParameter("string"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @GetMapping("/println2")
  @SuppressFBWarnings
  public void println2(final HttpServletRequest request, final HttpServletResponse response) {
    try {
      response.getWriter().println(request.getParameter("string").toCharArray());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @GetMapping("/printf")
  @SuppressFBWarnings
  public void printf(final HttpServletRequest request, final HttpServletResponse response) {
    try {
      String format = request.getParameter("string");
      response.getWriter().printf(format, "A", "B");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @GetMapping("/printf2")
  @SuppressFBWarnings
  public void printf2(final HttpServletRequest request, final HttpServletResponse response) {
    try {
      String format = "Formatted like: %1$s and %2$s.";
      response.getWriter().printf(format, "A", request.getParameter("string"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @GetMapping("/printf3")
  @SuppressFBWarnings
  public void printf3(final HttpServletRequest request, final HttpServletResponse response) {
    try {
      String format = request.getParameter("string");
      response.getWriter().printf(Locale.getDefault(), format, "A", "B");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @GetMapping("/printf4")
  @SuppressFBWarnings
  public void printf4(final HttpServletRequest request, final HttpServletResponse response) {
    try {
      String format = "Formatted like: %1$s and %2$s.";
      response.getWriter().printf(Locale.getDefault(), format, "A", request.getParameter("string"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @GetMapping("/format")
  @SuppressFBWarnings
  public void format(final HttpServletRequest request, final HttpServletResponse response) {
    try {
      String format = request.getParameter("string");
      response.getWriter().format(format, "A", "B");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @GetMapping("/format2")
  @SuppressFBWarnings
  public void format2(final HttpServletRequest request, final HttpServletResponse response) {
    try {
      String format = "Formatted like: %1$s and %2$s.";
      response.getWriter().format(format, "A", request.getParameter("string"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @GetMapping("/format3")
  @SuppressFBWarnings
  public void format3(final HttpServletRequest request, final HttpServletResponse response) {
    try {
      String format = request.getParameter("string");
      response.getWriter().format(Locale.getDefault(), format, "A", "B");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @GetMapping("/format4")
  @SuppressFBWarnings
  public void format4(final HttpServletRequest request, final HttpServletResponse response) {
    try {
      String format = "Formatted like: %1$s and %2$s.";
      response.getWriter().format(Locale.getDefault(), format, "A", request.getParameter("string"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @GetMapping(value = "/responseBody", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public String responseBody(final HttpServletRequest request, final HttpServletResponse response) {
    return request.getParameter("string");
  }
}
