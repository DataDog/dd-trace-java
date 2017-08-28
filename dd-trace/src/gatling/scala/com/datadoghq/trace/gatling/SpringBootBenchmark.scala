package com.datadoghq.trace.gatling

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._


class SpringBootBenchmark extends Simulation {

  val httpConf = http.baseURL("http://localhost:8080/")

  val scn = scenario("Spring Boot")
    .during(5 minutes) {
      exec(http("request_1").get("/demo/all"))
    }


  setUp(
    scn.inject(atOnceUsers(20))
  ).protocols(httpConf)
}
