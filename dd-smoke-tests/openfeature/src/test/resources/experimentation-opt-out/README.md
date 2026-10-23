# Experimentation opt-out policy cases

These 90 cases come from `DataDog/ffe-system-test-data@0520866bedb8c059df9c493cfe27ad294390c603`,
under `contracts/experimentation-opt-out/evidence/sdk-refresh-20261022/`.
Both Java implementation owners run the same cases through their production parser and evaluator.

The profile covers D002, D004, D010 and D011. It excludes browser timestamp grammar and transport behavior.
The Java parser grammar and existing provider initialization behavior remain unchanged.
Native tests separately cover omitted/null `doLog`, invalid activation on unreachable allocations, and telemetry.

Profile SHA256: `ad281f827a0e57a0aa4528157d5ab4a967bc0233cbae5d563f7408fe9f4017ff`.
Cases SHA256: `289a5d2dca4a7e5dd1e0428b4c56e9c1660668375aab67b71c91b27b9391f4e2`.
