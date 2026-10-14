# Security Policy

## Supported versions

Abada is in its release-candidate line. Security fixes are made on the latest release
candidate; please upgrade to it before reporting.

| Version | Supported |
| --- | --- |
| Latest `1.1.0-rc.x` | Yes |
| Older release candidates | No — upgrade to the latest |

## Reporting a vulnerability

**Do not open a public issue for security problems.**

Report privately through GitHub:
[Report a vulnerability](https://github.com/bashizip/abada-platform/security/advisories/new).

Please include:

- the affected component (`engine`, `studio`, `agent-worker`, `sdk/java`, installer, Compose files);
- the version or commit;
- steps to reproduce, and the impact you observed;
- whether the issue is already public.

You can expect an acknowledgement within 5 working days and a status update at least every
two weeks until the issue is resolved. We will credit you in the release notes unless you
prefer otherwise.

## Scope notes

- The development stack (`./release/abada-platform up dev`) ships well-known demo accounts
  and is **not** a production configuration. Reports that only apply to the dev stack are
  still welcome but are triaged as low severity.
- External side effects of workers are at-least-once by design (see
  [runtime semantics](docs/reference/runtime-semantics.md)); duplicated side effects are
  not a vulnerability unless an idempotency contract claims otherwise.
- An independent security review has not yet been performed; see the
  [deployment support matrix](docs/reference/deployment-support.md).
