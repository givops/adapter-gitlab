# GitLab adapter contributor instructions

Follow CONTRIBUTING.md. This repository is public Apache-2.0 Java 25 / Spring Boot
4.0.9 source. No private core, private fixtures, unpublished parent or credential
may be required to build it. Keep all new content in English and work in an
isolated task worktree. Run Maven verify for code changes. Default tests use
synthetic responses and local HTTPS only. Preserve security and resource bounds.

Before merging a PR, run `./scripts/verify-local.sh` on its final commit and
report the actual results. All verification belongs locally; GitHub Actions is
reserved for package releases. Keep every removed hosted check in the local
verification entrypoint. Do not skip tests to obtain a successful verification.
