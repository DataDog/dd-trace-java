# Benchmarks

This directory contains different types of benchmarks.

## Running Benchmarks via Docker

Docker allows the execution of benchmarks without needing to install and configure your development environment. For example, package installation and installation of sirun are performed automatically.

In order to run benchmarks using Docker, issue the following command from the `benchmark/` folder of this project:

```sh
./run.sh
```

If you run into storage errors (e.g. running out of disk space), try removing all unused Docker containers, networks, and images with `docker system prune -af` before running the script again. Once finished, the reports will be available in the `benchmark/reports/` folder. Note that the script can take ~40 minutes to run.

### Running specific benchmarks

If you want to run only a specific category of benchmarks, you can do so via arguments:

1. Run startup benchmarks
```sh
./run.sh startup [application]?
```

2. Run load benchmarks
```sh
./run.sh load [application]?
```
