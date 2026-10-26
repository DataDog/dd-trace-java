# Experimentation opt-out policy cases

These 90 cases come from `DataDog/ffe-system-test-data@64ea4a312f2167c7d48c7df642ae1e5d0a9327de`,
under `contracts/experimentation-opt-out/evidence/sdk-refresh-20261022/`.
The standalone provider and deprecated provider alias share the production parser and evaluator.
The native conformance test runs all 90 cases through that evaluator.
The profile and corpus are byte-identical to the original adoption revision.

The profile covers D002, D004, D010 and D011. It excludes browser timestamp grammar and transport behavior.
The Java parser grammar and existing provider initialization behavior remain unchanged.
Native tests separately cover omitted/null `doLog`, invalid activation on unreachable allocations, and telemetry.

Profile SHA256: `ad281f827a0e57a0aa4528157d5ab4a967bc0233cbae5d563f7408fe9f4017ff`.
Cases SHA256: `289a5d2dca4a7e5dd1e0428b4c56e9c1660668375aab67b71c91b27b9391f4e2`.
