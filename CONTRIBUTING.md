# Contributing to Abada

Thank you for considering a contribution. Abada is the open-source runtime where
**agents advise, rules decide, humans approve and PostgreSQL remembers** — and it gets
better with every bug report, example, doc fix and pull request.

You do not need to be a workflow-engine expert to help. Some of the most useful
contributions are small: a clearer sentence in the docs, a new example process, a
reproducible bug report.

## Ways to contribute

| You have… | Good place to start |
| --- | --- |
| 10 minutes | Install Abada and report anything that confused you ([bug report](https://github.com/bashizip/abada-platform/issues/new/choose)) |
| An hour | Pick an issue labelled [`good first issue`](https://github.com/bashizip/abada-platform/labels/good%20first%20issue) or [`documentation`](https://github.com/bashizip/abada-platform/labels/documentation) |
| A use case | Add an example process under [`examples/apl/`](examples/apl/) with a short README |
| Python / TypeScript / Go skills | Build a worker client for the [external worker protocol v1](docs/reference/external-worker-protocol-v1.md) — see [`sdk/java/`](sdk/java/) for the reference client |
| Java / Spring skills | Engine issues labelled [`help wanted`](https://github.com/bashizip/abada-platform/labels/help%20wanted) |
| React skills | Studio issues (`studio/`, React 19 + TypeScript + Vite) |

Before starting anything larger than a small fix, open an issue (or comment on an
existing one) so we can agree on the approach. It saves you from writing code that
conflicts with the [roadmap](docs/development/roadmap.md) or the runtime doctrine below.

## The doctrine every change respects

1. **The model runs outside the transaction.** Agent work is durable external work; its
   result enters process state only through an engine command.
2. **The engine enforces the contract.** Output schemas, confidence thresholds and allowed
   variables are checked by the engine, never trusted to a worker, a model or the UI.
3. **Deterministic decisions stay deterministic.** Conditions and decision tables are
   sandboxed CEL.
4. **Humans own change.** Nothing auto-applies.
5. **Evidence over claims.** Documentation only claims what code and tests prove.

The full working agreement — runtime invariants, migration rules, security requirements —
is in [`AGENTS.md`](AGENTS.md). Read it before changing the engine.

## Set up your environment

Requirements: Java 21, Node.js 24.18.x (see `.nvmrc`) with npm 11+, Docker with Compose v2.

```bash
git clone https://github.com/bashizip/abada-platform.git
cd abada-platform
./release/abada-platform doctor dev
./release/abada-platform up dev
```

Then run the checks for the part you are changing:

| Component | Commands |
| --- | --- |
| Engine (`engine/`) | `./mvnw test` (needs Docker: PostgreSQL Testcontainers) |
| Studio (`studio/`) | `npm ci && npm run lint && npm test && npm run build` |
| Documentation (`documentation/`) | `npm ci && npm run check && npm run build` |
| Agent worker (`agent-worker/`) | `./mvnw test` |

For engine development without the full stack:
`cd engine && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`.

## Pull requests

- Branch from **`dev`** and open your pull request against **`dev`** (not `main`).
- Keep each pull request focused on one change.
- Include tests with behaviour changes. PostgreSQL (via Testcontainers) is the reference
  for persistence, locking and concurrency — H2 results are not evidence.
- Update the documentation in the same pull request when behaviour changes.
- Never edit a released Flyway migration; add the next numbered one.
- Use [Conventional Commit](https://www.conventionalcommits.org/) messages:
  `feat(runtime): …`, `fix(api): …`, `docs(user): …`, `test(engine): …`.
- Say in the pull request which checks you ran and their result.

A maintainer aims to give first feedback on every issue and pull request within a few
days. If you have not heard back in a week, a friendly ping is welcome.

## Licensing of contributions

Abada is licensed under [AGPL-3.0-only](LICENSE); the Java worker SDK in `sdk/java/` is
[Apache-2.0](sdk/java/LICENSE). By submitting a contribution you agree that it is licensed
under the licence of the directory you change.

## Code of conduct and security

Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
Please report security vulnerabilities privately as described in [SECURITY.md](SECURITY.md),
not in public issues.
