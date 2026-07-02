package datadog.common.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.test.util.DDJavaSpecification;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

@SuppressForbidden
public class ContainerInfoTest extends DDJavaSpecification {

  // spotless:off
  @TableTest({
    "id | controllers           | path                                                                                                                            | containerId                                                        | podId                                  | line",
    // Docker examples
    "13 | [name=systemd]        | /docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860                                                      | 3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860  |                                        | 13:name=systemd:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860",
    "12 | [pids]                | /docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860                                                      | 3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860  |                                        | 12:pids:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860",
    "11 | [hugetlb]             | /docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860                                                      | 3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860  |                                        | 11:hugetlb:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860",
    "10 | [net_prio]            | /docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860                                                      | 3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860  |                                        | 10:net_prio:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860",
    " 9 | [perf_event]          | /docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860                                                      | 3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860  |                                        | 9:perf_event:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860",
    " 8 | [net_cls]             | /docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860                                                      | 3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860  |                                        | 8:net_cls:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860",
    " 7 | [freezer]             | /docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860                                                      | 3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860  |                                        | 7:freezer:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860",
    " 6 | [devices]             | /docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860                                                      | 3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860  |                                        | 6:devices:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860",
    " 5 | [memory]              | /docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860                                                      | 3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860  |                                        | 5:memory:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860",
    " 4 | [blkio]               | /docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860                                                      | 3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860  |                                        | 4:blkio:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860",
    " 3 | [cpuacct]             | /docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860                                                      | 3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860  |                                        | 3:cpuacct:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860",
    " 2 | [cpu]                 | /docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860                                                      | 3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860  |                                        | 2:cpu:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860",
    " 1 | [cpuset]              | /docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860                                                      | 3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860  |                                        | 1:cpuset:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860",
    // Kubernetes examples
    "11 | [perf_event]          | /kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1 | 3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1  | 3d274242-8ee0-11e9-a8a6-1e68d864ef1a   | 11:perf_event:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1",
    "10 | [pids]                | /kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1 | 3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1  | 3d274242-8ee0-11e9-a8a6-1e68d864ef1a   | 10:pids:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1",
    " 9 | [memory]              | /kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1 | 3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1  | 3d274242-8ee0-11e9-a8a6-1e68d864ef1a   | 9:memory:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1",
    " 8 | [cpu, cpuacct]        | /kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1 | 3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1  | 3d274242-8ee0-11e9-a8a6-1e68d864ef1a   | 8:cpu,cpuacct:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1",
    " 7 | [blkio]               | /kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1 | 3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1  | 3d274242-8ee0-11e9-a8a6-1e68d864ef1a   | 7:blkio:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1",
    " 6 | [cpuset]              | /kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1 | 3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1  | 3d274242-8ee0-11e9-a8a6-1e68d864ef1a   | 6:cpuset:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1",
    " 5 | [devices]             | /kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1 | 3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1  | 3d274242-8ee0-11e9-a8a6-1e68d864ef1a   | 5:devices:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1",
    " 4 | [freezer]             | /kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1 | 3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1  | 3d274242-8ee0-11e9-a8a6-1e68d864ef1a   | 4:freezer:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1",
    " 3 | [net_cls, net_prio]   | /kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1 | 3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1  | 3d274242-8ee0-11e9-a8a6-1e68d864ef1a   | 3:net_cls,net_prio:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1",
    " 2 | [hugetlb]             | /kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1 | 3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1  | 3d274242-8ee0-11e9-a8a6-1e68d864ef1a   | 2:hugetlb:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1",
    " 1 | [name=systemd]        | /kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1 | 3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1  | 3d274242-8ee0-11e9-a8a6-1e68d864ef1a   | 1:name=systemd:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1",
    // ECS examples
    " 9 | [perf_event]          | /ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce    | 38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce  |                                        | 9:perf_event:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce",
    " 8 | [memory]              | /ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce    | 38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce  |                                        | 8:memory:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce",
    " 7 | [hugetlb]             | /ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce    | 38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce  |                                        | 7:hugetlb:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce",
    " 6 | [freezer]             | /ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce    | 38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce  |                                        | 6:freezer:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce",
    " 5 | [devices]             | /ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce    | 38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce  |                                        | 5:devices:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce",
    " 4 | [cpuset]              | /ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce    | 38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce  |                                        | 4:cpuset:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce",
    " 3 | [cpuacct]             | /ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce    | 38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce  |                                        | 3:cpuacct:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce",
    " 2 | [cpu]                 | /ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce    | 38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce  |                                        | 2:cpu:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce",
    " 1 | [blkio]               | /ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce    | 38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce  |                                        | 1:blkio:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce",
    // Fargate examples
    "11 | [hugetlb]             | /ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da                    | 432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da  |                                        | 11:hugetlb:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da",
    "10 | [pids]                | /ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da                    | 432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da  |                                        | 10:pids:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da",
    " 9 | [cpuset]              | /ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da                    | 432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da  |                                        | 9:cpuset:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da",
    " 8 | [net_cls, net_prio]   | /ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da                    | 432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da  |                                        | 8:net_cls,net_prio:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da",
    " 7 | [cpu, cpuacct]        | /ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da                    | 432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da  |                                        | 7:cpu,cpuacct:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da",
    " 6 | [perf_event]          | /ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da                    | 432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da  |                                        | 6:perf_event:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da",
    " 5 | [freezer]             | /ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da                    | 432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da  |                                        | 5:freezer:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da",
    " 4 | [devices]             | /ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da                    | 432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da  |                                        | 4:devices:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da",
    " 3 | [blkio]               | /ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da                    | 432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da  |                                        | 3:blkio:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da",
    " 2 | [memory]              | /ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da                    | 432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da  |                                        | 2:memory:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da",
    " 1 | [name=systemd]        | /ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da                    | 432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da  |                                        | 1:name=systemd:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da",
    " 1 | [name=systemd]        | /ecs/34dc0b5e626f2c5c4c5170e34b10e765-1234567890                                                                               | 34dc0b5e626f2c5c4c5170e34b10e765-1234567890                        |                                        | 1:name=systemd:/ecs/34dc0b5e626f2c5c4c5170e34b10e765-1234567890",
    // PCF example
    " 1 | [freezer]             | /garden/6f265890-5165-7fab-6b52-18d1                                                                                           | 6f265890-5165-7fab-6b52-18d1                                       |                                        | 1:freezer:/garden/6f265890-5165-7fab-6b52-18d1",
    // Reference impl examples
    " 1 | [name=systemd]        | /system.slice/docker-cde7c2bab394630a42d73dc610b9c57415dced996106665d427f6d0566594411.scope                                     | cde7c2bab394630a42d73dc610b9c57415dced996106665d427f6d0566594411  |                                        | 1:name=systemd:/system.slice/docker-cde7c2bab394630a42d73dc610b9c57415dced996106665d427f6d0566594411.scope",
    " 1 | [name=systemd]        | /docker/051e2ee0bce99116029a13df4a9e943137f19f957f38ac02d6bad96f9b700f76/not_hex                                               |                                                                    |                                        | 1:name=systemd:/docker/051e2ee0bce99116029a13df4a9e943137f19f957f38ac02d6bad96f9b700f76/not_hex",
    " 1 | [name=systemd]        | /kubepods.slice/kubepods-burstable.slice/kubepods-burstable-pod90d81341_92de_11e7_8cf2_507b9d4141fa.slice/crio-2227daf62df6694645fee5df53c1f91271546a9560e8600a525690ae252b7f63.scope | 2227daf62df6694645fee5df53c1f91271546a9560e8600a525690ae252b7f63  | 90d81341_92de_11e7_8cf2_507b9d4141fa   | 1:name=systemd:/kubepods.slice/kubepods-burstable.slice/kubepods-burstable-pod90d81341_92de_11e7_8cf2_507b9d4141fa.slice/crio-2227daf62df6694645fee5df53c1f91271546a9560e8600a525690ae252b7f63.scope"
  })
  void cGroupInfoIsParsedFromIndividualLines(
      int id, List<String> controllers, String path, String containerId, String podId, String line)
      throws ParseException {
    ContainerInfo.CGroupInfo cGroupInfo = ContainerInfo.parseLine(line);

    assertEquals(id, cGroupInfo.getId());
    assertEquals(path, cGroupInfo.getPath());
    assertEquals(controllers, cGroupInfo.getControllers());
    assertEquals(containerId, cGroupInfo.getContainerId());
    assertEquals(podId, cGroupInfo.getPodId());
  }
  // spotless:on

  @ParameterizedTest
  @MethodSource("containerInfoParsedFromFileContentArguments")
  void containerInfoParsedFromFileContent(
      String containerId, String podId, int size, String content) throws Exception {
    ContainerInfo containerInfo = ContainerInfo.parse(content);

    assertEquals(containerId, containerInfo.getContainerId());
    assertEquals(podId, containerInfo.getPodId());
    assertEquals(size, containerInfo.getCGroups().size());
  }

  static Stream<Arguments> containerInfoParsedFromFileContentArguments() {
    // spotless:off
    return Stream.of(
        // Docker
        arguments("3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860", null, 13,
            "13:name=systemd:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860\n"
            + "12:pids:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860\n"
            + "11:hugetlb:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860\n"
            + "10:net_prio:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860\n"
            + "9:perf_event:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860\n"
            + "8:net_cls:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860\n"
            + "7:freezer:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860\n"
            + "6:devices:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860\n"
            + "5:memory:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860\n"
            + "4:blkio:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860\n"
            + "3:cpuacct:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860\n"
            + "2:cpu:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860\n"
            + "1:cpuset:/docker/3726184226f5d3147c25fdeab5b60097e378e8a720503a5e19ecfdf29f869860"),
        // Kubernetes
        arguments("3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1", "3d274242-8ee0-11e9-a8a6-1e68d864ef1a", 11,
            "11:perf_event:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1\n"
            + "10:pids:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1\n"
            + "9:memory:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1\n"
            + "8:cpu,cpuacct:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1\n"
            + "7:blkio:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1\n"
            + "6:cpuset:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1\n"
            + "5:devices:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1\n"
            + "4:freezer:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1\n"
            + "3:net_cls,net_prio:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1\n"
            + "2:hugetlb:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1\n"
            + "1:name=systemd:/kubepods/besteffort/pod3d274242-8ee0-11e9-a8a6-1e68d864ef1a/3e74d3fd9db4c9dd921ae05c2502fb984d0cde1b36e581b13f79c639da4518a1"),
        arguments("7b8952daecf4c0e44bbcefe1b5c5ebc7b4839d4eefeccefe694709d3809b6199", "2d3da189_6407_48e3_9ab6_78188d75e609", 1,
            "1:name=systemd:/kubepods.slice/kubepods-burstable.slice/kubepods-burstable-pod2d3da189_6407_48e3_9ab6_78188d75e609.slice/docker-7b8952daecf4c0e44bbcefe1b5c5ebc7b4839d4eefeccefe694709d3809b6199.scope"),
        // ECS
        arguments("38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce", null, 9,
            "9:perf_event:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce\n"
            + "8:memory:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce\n"
            + "7:hugetlb:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce\n"
            + "6:freezer:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce\n"
            + "5:devices:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce\n"
            + "4:cpuset:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce\n"
            + "3:cpuacct:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce\n"
            + "2:cpu:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce\n"
            + "1:blkio:/ecs/haissam-ecs-classic/5a0d5ceddf6c44c1928d367a815d890f/38fac3e99302b3622be089dd41e7ccf38aff368a86cc339972075136ee2710ce"),
        // Fargate 1.3-
        arguments("432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da", null, 11,
            "11:hugetlb:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "10:pids:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "9:cpuset:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "8:net_cls,net_prio:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "7:cpu,cpuacct:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "6:perf_event:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "5:freezer:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "4:devices:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "3:blkio:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "2:memory:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "1:name=systemd:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da"),
        // Fargate 1.4+
        arguments("34dc0b5e626f2c5c4c5170e34b10e765-1234567890", null, 11,
            "11:hugetlb:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "10:pids:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "9:cpuset:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "8:net_cls,net_prio:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "7:cpu,cpuacct:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "6:perf_event:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "5:freezer:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "4:devices:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "3:blkio:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "2:memory:/ecs/55091c13-b8cf-4801-b527-f4601742204d/432624d2150b349fe35ba397284dea788c2bf66b885d14dfc1569b01890ca7da\n"
            + "1:name=systemd:/ecs/34dc0b5e626f2c5c4c5170e34b10e765-1234567890"),
        // EKS Fargate with trailing cgroup v2 membership entry
        arguments("cf1241bbf80ea91eebdd28bf719057380997ca4b0cea16869393b905fb6d52bc", "defa568d-ff14-43d9-9a63-9e39ee9b39b4", 13,
            "12:misc:/ecs/545b896a072744d186c7fb09a45ec172/545b896a072744d186c7fb09a45ec172-3057940393/kubepods/burstable/poddefa568d-ff14-43d9-9a63-9e39ee9b39b4/cf1241bbf80ea91eebdd28bf719057380997ca4b0cea16869393b905fb6d52bc\n"
            + "11:cpuset:/ecs/545b896a072744d186c7fb09a45ec172/545b896a072744d186c7fb09a45ec172-3057940393/kubepods/burstable/poddefa568d-ff14-43d9-9a63-9e39ee9b39b4/cf1241bbf80ea91eebdd28bf719057380997ca4b0cea16869393b905fb6d52bc\n"
            + "10:perf_event:/ecs/545b896a072744d186c7fb09a45ec172/545b896a072744d186c7fb09a45ec172-3057940393/kubepods/burstable/poddefa568d-ff14-43d9-9a63-9e39ee9b39b4/cf1241bbf80ea91eebdd28bf719057380997ca4b0cea16869393b905fb6d52bc\n"
            + "9:blkio:/ecs/545b896a072744d186c7fb09a45ec172/545b896a072744d186c7fb09a45ec172-3057940393/kubepods/burstable/poddefa568d-ff14-43d9-9a63-9e39ee9b39b4/cf1241bbf80ea91eebdd28bf719057380997ca4b0cea16869393b905fb6d52bc\n"
            + "8:net_cls,net_prio:/ecs/545b896a072744d186c7fb09a45ec172/545b896a072744d186c7fb09a45ec172-3057940393/kubepods/burstable/poddefa568d-ff14-43d9-9a63-9e39ee9b39b4/cf1241bbf80ea91eebdd28bf719057380997ca4b0cea16869393b905fb6d52bc\n"
            + "7:memory:/ecs/545b896a072744d186c7fb09a45ec172/545b896a072744d186c7fb09a45ec172-3057940393/kubepods/burstable/poddefa568d-ff14-43d9-9a63-9e39ee9b39b4/cf1241bbf80ea91eebdd28bf719057380997ca4b0cea16869393b905fb6d52bc\n"
            + "6:cpu,cpuacct:/ecs/545b896a072744d186c7fb09a45ec172/545b896a072744d186c7fb09a45ec172-3057940393/kubepods/burstable/poddefa568d-ff14-43d9-9a63-9e39ee9b39b4/cf1241bbf80ea91eebdd28bf719057380997ca4b0cea16869393b905fb6d52bc\n"
            + "5:pids:/ecs/545b896a072744d186c7fb09a45ec172/545b896a072744d186c7fb09a45ec172-3057940393/kubepods/burstable/poddefa568d-ff14-43d9-9a63-9e39ee9b39b4/cf1241bbf80ea91eebdd28bf719057380997ca4b0cea16869393b905fb6d52bc\n"
            + "4:devices:/ecs/545b896a072744d186c7fb09a45ec172/545b896a072744d186c7fb09a45ec172-3057940393/kubepods/burstable/poddefa568d-ff14-43d9-9a63-9e39ee9b39b4/cf1241bbf80ea91eebdd28bf719057380997ca4b0cea16869393b905fb6d52bc\n"
            + "3:hugetlb:/ecs/545b896a072744d186c7fb09a45ec172/545b896a072744d186c7fb09a45ec172-3057940393/kubepods/burstable/poddefa568d-ff14-43d9-9a63-9e39ee9b39b4/cf1241bbf80ea91eebdd28bf719057380997ca4b0cea16869393b905fb6d52bc\n"
            + "2:freezer:/ecs/545b896a072744d186c7fb09a45ec172/545b896a072744d186c7fb09a45ec172-3057940393/kubepods/burstable/poddefa568d-ff14-43d9-9a63-9e39ee9b39b4/cf1241bbf80ea91eebdd28bf719057380997ca4b0cea16869393b905fb6d52bc\n"
            + "1:name=systemd:/ecs/545b896a072744d186c7fb09a45ec172/545b896a072744d186c7fb09a45ec172-3057940393/kubepods/burstable/poddefa568d-ff14-43d9-9a63-9e39ee9b39b4/cf1241bbf80ea91eebdd28bf719057380997ca4b0cea16869393b905fb6d52bc\n"
            + "0::/ecs/545b896a072744d186c7fb09a45ec172/545b896a072744d186c7fb09a45ec172-3057940393"),
        // PCF
        arguments("6f265890-5165-7fab-6b52-18d1", null, 12,
            "12:rdma:/\n"
            + "11:net_cls,net_prio:/garden/6f265890-5165-7fab-6b52-18d1\n"
            + "10:freezer:/garden/6f265890-5165-7fab-6b52-18d1\n"
            + "9:devices:/system.slice/garden.service/garden/6f265890-5165-7fab-6b52-18d1\n"
            + "8:blkio:/system.slice/garden.service/garden/6f265890-5165-7fab-6b52-18d1\n"
            + "7:pids:/system.slice/garden.service/garden/6f265890-5165-7fab-6b52-18d1\n"
            + "6:memory:/system.slice/garden.service/garden/6f265890-5165-7fab-6b52-18d1\n"
            + "5:cpuset:/garden/6f265890-5165-7fab-6b52-18d1\n"
            + "4:cpu,cpuacct:/system.slice/garden.service/garden/6f265890-5165-7fab-6b52-18d1\n"
            + "3:perf_event:/garden/6f265890-5165-7fab-6b52-18d1\n"
            + "2:hugetlb:/garden/6f265890-5165-7fab-6b52-18d1\n"
            + "1:name=systemd:/system.slice/garden.service/garden/6f265890-5165-7fab-6b52-18d1")
    );
    // spotless:on
  }

  @Test
  void containerInfoFromEmptyFileIsEmpty() throws Exception {
    File f = File.createTempFile("container-info-test-", "-empty-file");
    f.deleteOnExit();
    Path p = Paths.get(f.getPath());

    ContainerInfo containerInfo = ContainerInfo.fromProcFile(p);

    assertNull(containerInfo.getContainerId());
    assertNull(containerInfo.getPodId());
    assertEquals(0, containerInfo.getCGroups().size());
  }

  @Test
  void containerInfoThrowsParseExceptionWhenGivenMalformedProcfile() throws Exception {
    File f = File.createTempFile("container-info-test-", "-malformed-file");
    f.deleteOnExit();
    Files.write(f.toPath(), "This is not valid".getBytes());
    Path p = Paths.get(f.getPath());

    assertThrows(ParseException.class, () -> ContainerInfo.fromProcFile(p));
  }

  @Test
  void containerInfoToleratesMissingContainerIdAndPodIdInProcfile() throws Exception {
    File f = File.createTempFile("container-info-test-", "-missing-container-id");
    f.deleteOnExit();
    Files.write(f.toPath(), "1:cpuset:fake-path".getBytes());
    Path p = Paths.get(f.getPath());

    ContainerInfo containerInfo = ContainerInfo.fromProcFile(p);

    assertNull(containerInfo.getContainerId());
    assertNull(containerInfo.getPodId());
    assertEquals(1, containerInfo.getCGroups().size());
  }

  @Test
  void getInoPathShouldReturnSameValueAsLsIdPath() throws Exception {
    File f = File.createTempFile("container-info-test-", "-inode-file");
    f.deleteOnExit();
    Path path = f.toPath();

    assertEquals(readInode(path), ContainerInfo.readInode(path));
  }

  // spotless:off
  @TableTest({
    "cid          | isHostCgroupNamespace",
    "cid          | true                 ",
    "containerId  | false                "
  })
  void readEntityIDReturnCidContainerIdIfContainerIdIsDefined(
      String cid, boolean isHostCgroupNamespace) {
    ContainerInfo containerInfo = new ContainerInfo();
    containerInfo.setContainerId(cid);

    assertEquals("cid-" + cid,
        ContainerInfo.readEntityID(containerInfo, true, Paths.get("/sys/fs/cgroup")));
  }
  // spotless:on

  @TableTest({"cid", "   ", "'' "})
  void readEntityIDReturnNullIfContainerIdIsNotDefinedAndIsHostCgroupNamespace(String cid) {
    ContainerInfo containerInfo = new ContainerInfo();
    containerInfo.setContainerId(cid);

    assertNull(ContainerInfo.readEntityID(containerInfo, true, Paths.get("/sys/fs/cgroup")));
  }

  // spotless:off
  @TableTest({
    "controllers          | hasEntityId",
    "['', memory]         | true       ",
    "[memory, '']         | true       ",
    "['']                 | true       ",
    "[memory]             | false      "
  })
  void readEntityIDReturnIdInoForEmptyController(
      List<String> controllers, boolean hasEntityId) throws Exception {
    File mountPath = createTempDir();
    File file = File.createTempFile("container-info-test-", "-inode-file", mountPath);
    file.deleteOnExit();
    long ino = readInode(file.toPath());

    ContainerInfo containerInfo = new ContainerInfo();
    ContainerInfo.CGroupInfo cGroupInfo = new ContainerInfo.CGroupInfo();
    cGroupInfo.setControllers(controllers);
    cGroupInfo.setPath(file.getName());
    containerInfo.setcGroups(Arrays.asList(cGroupInfo));

    String expected = hasEntityId ? "in-" + ino : null;
    assertEquals(expected, ContainerInfo.readEntityID(containerInfo, false, mountPath.toPath()));
  }

  @TableTest({
    "controllers          | hasEntityId",
    "['', memory]         | true       ",
    "[memory, '']         | true       ",
    "[memory]             | true       ",
    "['']                 | false      "
  })
  void readEntityIDReturnIdInoForMemoryController(
      List<String> controllers, boolean hasEntityId) throws Exception {
    File mountPath = createTempDir();
    File memoryController =
        Files.createDirectory(mountPath.toPath().resolve("memory")).toFile();
    memoryController.deleteOnExit();
    File file = File.createTempFile("container-info-test-", "-inode-file", memoryController);
    file.deleteOnExit();
    long ino = readInode(file.toPath());

    ContainerInfo containerInfo = new ContainerInfo();
    ContainerInfo.CGroupInfo cGroupInfo = new ContainerInfo.CGroupInfo();
    cGroupInfo.setControllers(controllers);
    cGroupInfo.setPath(file.getName());
    containerInfo.setcGroups(Arrays.asList(cGroupInfo));

    String expected = hasEntityId ? "in-" + ino : null;
    assertEquals(expected, ContainerInfo.readEntityID(containerInfo, false, mountPath.toPath()));
  }
  // spotless:on

  @Test
  void readEntityIDReturnIdInoForParentWhenPathIsSlash() throws Exception {
    File mountPath = createTempDir();
    File memoryController = Files.createDirectory(mountPath.toPath().resolve("memory")).toFile();
    memoryController.deleteOnExit();
    long ino = readInode(memoryController.toPath());

    ContainerInfo containerInfo = new ContainerInfo();
    ContainerInfo.CGroupInfo cGroupInfo = new ContainerInfo.CGroupInfo();
    cGroupInfo.setControllers(Arrays.asList("memory"));
    cGroupInfo.setPath("/");
    containerInfo.setcGroups(Arrays.asList(cGroupInfo));

    assertEquals("in-" + ino, ContainerInfo.readEntityID(containerInfo, false, mountPath.toPath()));
  }

  private static File createTempDir() throws IOException {
    File dir = File.createTempFile("container-info-test-", "-sys-fs-cgroup");
    dir.delete();
    dir.mkdirs();
    dir.deleteOnExit();
    return dir;
  }

  private static long readInode(Path path) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder("ls", "-id", path.toString());
    Process ps = pb.start();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(ps.getInputStream()))) {
      String line = reader.readLine();
      ps.waitFor();
      return Long.parseLong(line.substring(0, line.indexOf(' ')));
    }
  }
}
