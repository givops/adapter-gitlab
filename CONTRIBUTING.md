# Contributing

Use Java 25 and Maven 3.9 or newer. Run `./scripts/build.sh` to build the public
SDK at `sdk-revision.txt` and verify this repository. For a coordinated SDK change,
set `GIVOPS_SDK_SOURCE` to the isolated SDK checkout. Do not depend on private
GivOps source, internal Maven artifacts, credentials or fixture repositories.

Create a task branch and isolated worktree. Keep changes and authored content in
English. Preserve Apache-2.0 licensing. Update the README matrix when an endpoint,
product-version decision, credential field or capability changes.

Add focused native response fixtures and contract tests when changing mappings.
Use synthetic data and the local HTTP fixture; never commit real tokens, provider
error payloads or generated TLS keys. Default `mvn verify` must stay offline with
respect to GitLab. Local HTTPS tests require permission to bind a loopback socket.

Retain whole-body request deadlines, byte/object/page limits, origin/context pins,
redirect refusal, cancellation and host credential revalidation. Never retry a
write automatically. Keep product-version observations separate from SDK/artifact
compatibility and avoid global credential/version caches. Tests should prove
behavior across older/newer profiles, denied access and ambiguous/missing data.

Before submitting, run `mvn clean verify`, `git diff --check` and inspect the JAR's
single metadata entry and Boot auto-configuration imports. Changes to public SDK
contracts must be coordinated with the SDK repository; do not duplicate host
interfaces into this provider.
