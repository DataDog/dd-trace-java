# Releases
Datadog will generally create a new minor release the first full week of every month.

## Release Types
The release workflow depends on the release type:

- **Minor release**: The **default release workflow** done on a monthly basis. It will ship the latest changes from the `master` branch.
- **Patch release**: Done when needed only. It will start from a minor release branch (release/vM.N.x) and includes important fixes only.
- **Major release**: Done exceptionally. It marks an important compatibility break.
- **Snapshot release**: Done automatically for every PR.

In addition to the release types, most releases can be:
- **Release candidate**: Major, minor and patch release can be candidate, meaning they won’t be promoted, but can still be used for testing.
