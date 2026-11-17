# GivOps GitLab adapter

One Apache-2.0 library supplies **GitLab ticketing and forge** through the public
[GivOps adapter SDK](https://github.com/givops/adapter-sdk). The private GivOps
application is not a build dependency. Java 25 and Spring Boot 4.0.9 are the
supported runtime baseline. Install `adapter-gitlab-0.3.0-SNAPSHOT.jar` with a host
supporting SDK API **0.3**, then restart it. Either domain can be configured alone.

## Build and test

```sh
./scripts/build.sh
```

The script builds the pinned public SDK source and runs `mvn clean verify` here.
For coordinated source development, set `GIVOPS_SDK_SOURCE=/path/to/adapter-sdk`.
No private Maven repository, host checkout, fixture artifact or account is needed.
Tests use representative synthetic JSON, mocked HTTP and a local TLS server;
there are no default live-provider tests. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Architecture and installation

`META-INF/givops-adapter.properties` advertises one provider artifact with
`domains=ticketing,forge`. Boot auto-configuration registers separate public
provider descriptors and ports. Domain implementations activate when the host
supplies their SDK configuration; ticketing additionally requires `CredentialHost`
and `ContextSigner`. Both domains use the same transport, URL, pagination, version
and payload validation primitives. Domain limits have separate transport instances
so changing ticket limits does not alter forge request budgets.

The host owns credential storage, approved destinations, request budgets,
revocation, authorization, transactions and snapshot publication. The adapter
constructs GitLab authentication and vendor requests, maps native responses,
normalizes lifecycle/CI/conflict signals, and reports scoped capability evidence.
It has no dependency on host persistence, private credentials or test fixtures.

## Connections and authentication

Use an HTTPS instance base URL, optionally with GitLab's installation context
path, for example `https://gitlab.example.org/gitlab`. Paths with credentials,
queries, fragments, escaped context components or dot segments are refused.
Managed credentials expose one secret field, `apiToken`; requests use GitLab's
`PRIVATE-TOKEN` header. Grant `read_api` for reads or `api` for writes and the
required project membership. Actual project and token permissions remain decisive.
No credential value is returned in errors or diagnostic `toString()` output.

Runtime credential fallback is supported through the same definition for both
domains, with a mandatory destination pin:

```yaml
givops:
  forge:
    gitlab:
      instances:
        example:
          token: ${GITLAB_API_TOKEN}
          expected-base-url: https://gitlab.example.org
          access-revision: rotation-1
          hosting: SELF_MANAGED
```

Ticketing captures a host credential lease, checks its instance and base-URL
pins, and revalidates before each HTTP attempt and before publishing results.
Rotation/revocation invalidates captured access. Forge receives host-captured
access and a host admission gate. Anonymous forge reads and identity lookup remain
supported. Native ticketing currently requires a token.

All requests retain TLS verification, refuse redirects, use connect and total-body
timeouts, and count response bytes and objects. Remote pagination links are
validated against the original destination/path and filters, then regenerated.
The adapter never forwards remote links with credentials. Reads and writes do not
automatically retry; an interrupted write may have succeeded remotely and must be
reconciled before a new explicit command.

## Native ticketing mapping

Bind an existing GitLab project using its numeric ID or complete namespace path;
its project key is the complete path, such as `team/payments`. Binding is read-only
and never creates a repository. The remote response must match the bound identity.

Issue keys are `team/payments#7`; `#7` is resolved only within the bound project.
Issue URLs support instance context paths. Project identity plus global issue ID
prevents IID collisions; instance isolation remains the host's responsibility.
Reads preserve labels, native state, timestamps, first assignee and
milestone membership. Selected issue lookups and complete selected milestone
membership use bounded requests. A missing issue is unresolved; authorization
failures never silently become missing data.

### Transient ticket browser

`ticketing.browse`, `ticketing.content` and `ticketing.navigation` are registered
through the SDK browser ports. Browsing validates the bound project and enumerates
its issues with `GET /projects/:id/issues` (`scope=all`, `state=all`). It applies
case-insensitive, NFC-normalized literal key/title search and native status, native
issue type and label filters locally. Filter families combine with AND; values
within each family combine with OR. This includes labels: GitLab's native
comma-separated labels filter requires all labels and is deliberately not used.
Description text is excluded from search and from observation persistence.

The browser sorts local issue numbers numerically (`key-asc`, `key-desc`) or by
update timestamp (`updated-asc`, `updated-desc`, with issue number as a tie break).
Missing update timestamps sort first in ascending order. Both opened and closed
issues use the ACTIVE lifecycle; opening maps to OPEN outcome, while closure has
UNKNOWN outcome because GitLab does not distinguish success from cancellation.
ARCHIVED and DRAFT lifecycle browsing is not advertised.

Every list operation scans the complete bounded collection before filtering and
returning a page. Exceeding page, object, request or byte limits fails the operation
instead of returning an incomplete set. Continuations bind the query, page size,
project binding and access context. The host additionally signs its browser
context and cursor. Each continuation rereads live data: changes can shift offsets
and cause omissions or duplicates between pages. Counts describe the observed
scan, not an atomic snapshot. Mutable `gitlab-live` source revisions identify the
bound access scope; `snapshot=false` makes this limitation explicit. No remote
version cache or server-side frozen snapshot is claimed.

Detail and content operations resolve the global issue ID from a fresh bounded
project issue scan, then fetch `GET /projects/:id/issues/:issue_iid` and validate
the global ID, IID, project and provider URL again. They never use a global ID as
an IID or require GitLab's administrator-only global-ID detail endpoint. Path
aliases remain scoped to the host binding while issue identity stays stable.
The extra scan means very large projects may exceed configured browser budgets.

Content returns the current raw Markdown description transiently, with an absent
description represented as empty text. It checks the host gate, current credential
lease and content byte limit. Descriptions, comments and body assets never enter
observation persistence. `content=true`, `assets=false`: attachment bytes are not
fetched or proxied, and no attachment or resolved-link metadata is advertised.
The host renders or sanitizes the Markdown under its own content policy. Existing
creation/write descriptions and forge merge-request descriptions are preserved.

Project milestones provide versions. Native `active`/`closed`, start date and due
date are preserved. `released` and `archived` remain false: closing a milestone
is not a Jira release. GitLab REST does not expose a milestone closure timestamp
in the common profile; it remains unknown. Parent-group milestones are excluded
from project-version lists. Membership uses the milestone-ID endpoint to avoid
ambiguous title filtering. Duplicate milestone titles yield an ambiguous mapping.

Explicit supported commands:

| Command | Remote operation | Details |
| --- | --- | --- |
| Project creation | `POST /projects` | Project slug/name/description; optional namespace ID in the owner field |
| Version creation | `POST /projects/:id/milestones` | Creates an active milestone with title and optional due date |
| Issue creation | `POST /projects/:id/issues` | Title/body, one project milestone, explicit or current-user assignee |

Issue creation supports native `issue`, `incident`, `test_case` and `task` types
when the remote instance permits them. Portable kind and priority become
`type::<kind>` and `priority::<priority>` labels. Native issues begin `opened`;
other requested workflow states, vendor priorities and release/archive commands
are rejected before mutation. Release reports are not advertised.

## Forge behavior

Preserves open merge requests, commit associations and their cache precedence,
current merge-request head pipelines, branch pipelines selected by exact ref/SHA,
discussions, approvals, connection probes and explicit pipeline commands.
Provider lifecycle, conflict and CI states are normalized in this library.
Discovery preserves its existing **Self-Managed only** hosting boundary; SaaS,
Dedicated and unknown hosting do not advertise discovery. Reads and repository
identity are independent of discovery support.

## Product versions and API profiles

SDK API `0.3`, adapter artifact `0.3.0-SNAPSHOT`, GitLab product version and remote
API profiles are separate values. Profiles are observed per instance/project and
credentials; there is no cross-tenant version or permission cache.

| Product | API used | Behavior |
| --- | --- | --- |
| GitLab 17 | REST v4 issues, project milestones, projects, merge requests, pipelines | Common read/create endpoints plus bounded browser list/detail/Markdown content; native fixtures and HTTP contracts |
| GitLab 17.0–17.9 | Pipeline input schema | Rejected after version detection before GraphQL/input mutation |
| GitLab 17.10+ | `ciPipelineCreationInputs`, pipeline `inputs` | Remote feature flag/schema must permit the feature |
| GitLab 18.1+ and 19 | Same REST v4 plus pipeline input schema | Pipeline `inputs` is generally available; project permissions still apply |

`GET /metadata` supplies product evidence; only HTTP 404 falls back to `GET /version`.
Ordinary ticket reads do not require a metadata request. Missing/forbidden metadata
never implies an unsupported product. Pipeline schema failures remain unsupported;
dynamic input rules are rejected rather than guessed. There is no artificial
GitLab-19-only threshold for the common issue/milestone APIs.

Capability inspection distinguishes `AVAILABLE`, `UNSUPPORTED`, `UNKNOWN`,
`FORBIDDEN`, `TEMPORARILY_UNAVAILABLE` and `DISABLED`. Readability is tested against
the selected project endpoint. Read-only preflight cannot prove every write
permission or feature flag, so writes may remain `UNKNOWN` until an explicit
operation supplies evidence.

Official endpoint histories and contracts used for this implementation:
[issues](https://docs.gitlab.com/api/issues/),
[project milestones](https://docs.gitlab.com/api/milestones/),
[pipeline inputs history](https://docs.gitlab.com/api/pipelines/#create-a-new-pipeline),
[GraphQL schema](https://docs.gitlab.com/api/graphql/reference/objects/#project),
[metadata and version](https://docs.gitlab.com/api/metadata/).
The 17/19 matrix describes the tested API profiles, not a claim that this suite
has exercised live servers of those versions. Live validation is a separate,
explicit step using disposable projects and supplied credentials.

### SDK API 0.3 compatibility

This source requires the exact public SDK API 0.3 and artifact version
0.3.0-SNAPSHOT. Observation acquisition is metadata-only: issue descriptions and
comments are not mapped. Existing creation/write descriptions and forge
merge-request descriptions retain their existing behavior. This provider advertises
optional transient browsing and Markdown content, without assets or immutable
snapshots. The `ticketing.navigation` capability supplies provider-owned project
space links without a content fetch. Detail supplies a validated ticket link after
resolving the stable global ID to its project IID; navigation alone leaves an
unknown ticket link absent.

The SDK source pin selects public commit
[`18a1a9c1103d2b8793f900769c389771c1debded`](https://github.com/givops/adapter-sdk/commit/18a1a9c1103d2b8793f900769c389771c1debded),
which supplies API 0.3. The repository's default build uses that public SDK source;
no unpublished SDK checkout is required. Browser support is available on the
`task/provider-browser` source branch. The artifact remains a development snapshot.
