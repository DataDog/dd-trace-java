package datadog.smoketest.springboot.controller;

import datadog.smoketest.springboot.AbstractIastWebController;
import datadog.smoketest.springboot.TestBean;
import javax.servlet.http.HttpServletRequest;
import javax.websocket.server.PathParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IastWebController extends AbstractIastWebController {

  @Override
  @RequestMapping("/greeting")
  public String greeting() {
    return super.greeting();
  }

  @Override
  @RequestMapping("/weakhash")
  public String weakhash() {
    return super.weakhash();
  }

  @Override
  @RequestMapping("/async_weakhash")
  public String asyncWeakhash() {
    return super.asyncWeakhash();
  }

  @Override
  @RequestMapping("/getparameter")
  public String getParameter(@RequestParam String param, HttpServletRequest request) {
    return super.getParameter(param, request);
  }

  @Override
  @GetMapping("/cmdi/runtime")
  public String commandInjectionRuntime(final HttpServletRequest request) {
    return super.commandInjectionRuntime(request);
  }

  @Override
  @GetMapping("/cmdi/process_builder")
  public String commandInjectionProcessBuilder(final HttpServletRequest request) {
    return super.commandInjectionProcessBuilder(request);
  }

  @Override
  @GetMapping("/path_traversal/file")
  public String pathTraversalFile(final HttpServletRequest request) {
    return super.pathTraversalFile(request);
  }

  @Override
  @GetMapping("/path_traversal/paths")
  public String pathTraversalPaths(final HttpServletRequest request) {
    return super.pathTraversalPaths(request);
  }

  @Override
  @GetMapping("/path_traversal/path")
  public String pathTraversalPath(final HttpServletRequest request) {
    return super.pathTraversalPath(request);
  }

  @Override
  @GetMapping("/param_binding/test")
  public String paramBinding(final TestBean testBean) {
    return super.paramBinding(testBean);
  }

  @Override
  @GetMapping("/request_header/test")
  public String requestHeader(@RequestHeader("test-header") String header) {
    return super.requestHeader(header);
  }

  @Override
  @GetMapping("/path_param")
  public String pathParam(@PathParam("param") String param) {
    return super.pathParam(param);
  }

  @Override
  @PostMapping("/request_body/test")
  public String jsonRequestBody(@RequestBody TestBean testBean) {
    return super.jsonRequestBody(testBean);
  }
}
