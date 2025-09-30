package test.apms8174

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

import javax.annotation.Nonnull
import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [TestController, TestConfig])
@EnableWebMvc
@AutoConfigureMockMvc
class ServletPathRequestFilterTest extends InstrumentationSpecification {

  static class TestConfig {
    @Bean
    WebMvcConfigurer webMvcConfigurer() {
      return new WebMvcConfigurer() {
          @Override
          void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override
                boolean preHandle(@Nonnull HttpServletRequest request, @Nonnull HttpServletResponse response, @Nonnull Object handler) throws Exception {
                  if ("true".equalsIgnoreCase(request.getHeader("fail"))) {
                    throw new RuntimeException("Stop here")
                  }
                  return true
                }
              })
          }
        }
    }
  }

  @Autowired
  private MockMvc mvc

  def 'should extract resourceName when preHandle succeed'() {
    setup:
    def request = get(PATH_PARAM.getPath())

    when:
    def response = mvc.perform(request).andExpect(status().is(PATH_PARAM.getStatus()))

    then:
    //check the response
    response.andExpect(MockMvcResultMatchers.content().string(PATH_PARAM.getBody()))

    assertTraces(1) {
      sortSpansByStart()
      trace(3) {
        span {
          resourceName "GET " + TestController.PATH_PATTERN
          spanType DDSpanTypes.HTTP_SERVER
        }
        span {
          resourceName "TestController.path_param"
          spanType DDSpanTypes.HTTP_SERVER
        }
        span {
          resourceName "controller"
        }
      }
    }
  }

  def 'should extract resourceName when preHandle throws'() {
    setup:
    def request = get(PATH_PARAM.getPath()).header("fail", "true")

    when:
    mvc.perform(request)

    then:
    //check the response
    thrown(ServletException)
    and:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "GET " + TestController.PATH_PATTERN
          spanType DDSpanTypes.HTTP_SERVER
          errored true
        }
      }
    }
  }
}
