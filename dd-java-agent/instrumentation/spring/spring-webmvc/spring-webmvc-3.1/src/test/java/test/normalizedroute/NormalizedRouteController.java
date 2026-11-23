package test.normalizedroute;

import static org.springframework.web.bind.annotation.RequestMethod.GET;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Annotated AntPathMatcher routes of {@link NormalizedRouteAppConfig}. */
@RestController
public class NormalizedRouteController {

  @RequestMapping(value = "/regex/{id:\\d+}", method = GET)
  public String regex() {
    return "ok";
  }

  @RequestMapping(value = "/nested/{code:[0-9]{3}}", method = GET)
  public String nestedRegex() {
    return "ok";
  }

  @RequestMapping(value = "/files/*.txt", method = GET)
  public String singleStar() {
    return "ok";
  }

  @RequestMapping(value = "/static/**", method = GET)
  public String trailingDoubleStar() {
    return "ok";
  }

  @RequestMapping(value = "/mixed/{id}/**", method = GET)
  public String namedAndDoubleStar() {
    return "ok";
  }

  @RequestMapping(value = "/star/*/**", method = GET)
  public String starAndDoubleStar() {
    return "ok";
  }

  @RequestMapping(value = "/mid/**/end", method = GET)
  public String midPathDoubleStar() {
    return "ok";
  }
}
