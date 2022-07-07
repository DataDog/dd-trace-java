# Spring Boot RabbitMQ Smoke Test

This smoke test can be run by itself, or in a _circle_ of participating nodes. It takes the following command line arguments:

* `--server.port={port}`
  * Sets the HTTP server listen port
  * Defaults to `8888`
* `--spring.main.web-application-type=none`
  * Disables the HTTP server
* `--rabbit.receiver.queue={queue}`
  * Sets the name of the queue where messages are received
  * Defaults to `queue`
* `--rabbit.sender.queue={queue}`
  * Sets the name of the queue where messages are sent
  * Defaults to `queue`
* `--rabbit.receiver.forward={true|false}`
  * Should the receiver just forward messages to the sending queue?
  * Defaults to `false`

The HTTP server accepts requests on `http://{host}:{port}/roundtrip/{message}`

Here is an example with two participating nodes that use the queues `queue` and `otherqueue` to communicate.

```
terminal1> docker run -d --hostname my-rabbit --name some-rabbit -p 5672:5672 rabbitmq:3.9.20-alpine

terminal1> java -Ddd.service.name=spring-rabbit-1 -jar build/libs/build/libs/spring-boot-rabbit-X.Y.Z-all.jar --rabbit.sender.queue=otherqueue

terminal2> java -Ddd.service.name=spring-rabbit-2 -jar build/libs/build/libs/spring-boot-rabbit-X.Y.Z-all.jar --spring.main.web-application-type=none --rabbit.receiver.queue=otherqueue --rabbit.receiver.forward=true
```

Then you can make requests, i.e. `curl "http://localhost:8888/roundtrip/foo"`, and the request will go to `spring-rabbit-1`, which sends a message to `spring-rabbit-2`, which sends a message back to `spring-rabbit-1`, which responds to the HTTP request.
