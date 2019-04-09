# Datadog Java Profiling Agent/Library
This repo contains the Datadog Profiling agent. It can either be used to embed with another Java Agent, for example the APM Java Agent, or as a stand along JPLIS agent. 

The library provides functionality to create and dump flight recordings, and to upload the recordings with the appropriate metadata to an http based edge service.

## Building the Agent
In the root folder, simply run:

```bash
mvn clean package
```

If you want to use the dependencies elsewhere, run:

```bash
mvn install
```

## Running the Agent
To run the agent with a Java process, add the built agent to the process using the -javaagent command line flag, like so:

```bash
java MyLovelyProgram -javaagent:${project_loc}/target/profiling-javaagent-0.0.1-SNAPSHOT.jar=<agentproperties.txt>
```

If the project is imported into Eclipse, the agent comes with a little test program and a launcher which starts it with agent. To run with the externally built agent, simply run the TakeBuiltAgentForASpin launcher. Don't forget to first build everything with maven.
