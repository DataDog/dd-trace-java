# Benchmarks

This directory contains different types of benchmarks.

## Running Benchmarks via Docker

Docker allows the execution of benchmarks without needing to install and configure your development environment. For example, package installation and installation of sirun is performed automatically.

In order to run benchmarks using Docker, issue the following command from the benchmark folder of the project:

```sh
./run.sh
```

Once it finishes, the reports will be available in the reports folder.

### Running specific benchmarks

If you want to run only a specific category of benchmarks you can do it via arguments:

1. Run startup benchmarks
```sh
./run.sh startup [application]?
```

2. Run load benchmarks
```sh
./run.sh load [application]?
```
