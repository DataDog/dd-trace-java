package test.tt

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.tt.TransactionTrackingCandidateSources
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.test.web.servlet.MockMvc
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.config.annotation.EnableWebMvc

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

@SpringBootTest(classes = TtCandidateSourcesSpringBootTest.TtController)
@EnableWebMvc
@AutoConfigureMockMvc
class TtCandidateSourcesSpringBootTest extends InstrumentationSpecification {

  @Controller
  static class TtController {
    @RequestMapping(value = "/tt", method = [RequestMethod.GET])
    ResponseEntity<String> tt() {
      return new ResponseEntity<>("ok", HttpStatus.OK)
    }
  }

  @Autowired
  private MockMvc mvc

  def cleanup() {
    TransactionTrackingCandidateSources.resetForTest()
  }

  def 'sets _dd.tt.candidate_sources for mixed header and qs matches'() {
    setup:
    TransactionTrackingCandidateSources.update(["x-trace-*", "tenant", "*-id"])

    when:
    mvc.perform(
      get("/tt?tenant=42&debug=1&request-id=abc")
      .header("X-Trace-Id", "t1")
      .header("X-Trace-Source", "test")
      .header("Authorization", "secret")
      ).andExpect({ res -> assert res.response.status == 200 })

    TEST_WRITER.waitForTraces(1)
    def serverSpan = TEST_WRITER.firstTrace().find { it.spanType == DDSpanTypes.HTTP_SERVER }

    then:
    serverSpan != null
    serverSpan.getTag(InstrumentationTags.TT_CANDIDATE_SOURCES) ==
      "header:x-trace-id,header:x-trace-source,qs:request-id,qs:tenant"
  }

  def 'does not set the tag when the pattern list is empty'() {
    setup:
    TransactionTrackingCandidateSources.resetForTest()
    assert TransactionTrackingCandidateSources.isEmpty()

    when:
    mvc.perform(
      get("/tt?tenant=42")
      .header("X-Trace-Id", "t1")
      ).andExpect({ res -> assert res.response.status == 200 })

    TEST_WRITER.waitForTraces(1)
    def serverSpan = TEST_WRITER.firstTrace().find { it.spanType == DDSpanTypes.HTTP_SERVER }

    then:
    serverSpan != null
    serverSpan.getTag(InstrumentationTags.TT_CANDIDATE_SOURCES) == null
  }
}
