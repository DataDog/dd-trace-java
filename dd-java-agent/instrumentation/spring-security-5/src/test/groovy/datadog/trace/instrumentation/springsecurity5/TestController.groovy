package datadog.trace.instrumentation.springsecurity5

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody

import static datadog.trace.instrumentation.springsecurity5.TestEndpoint.SUCCESS


@Controller
class TestController {

    @RequestMapping("/success")
    @ResponseBody
    String success() {
        SpringBootBasedTest.controller(SUCCESS) {
            SUCCESS.body
        }
    }
}