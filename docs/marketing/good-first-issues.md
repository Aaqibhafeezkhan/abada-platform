# Issue drafts to seed

Create these on GitHub with the listed labels (maintainer action). A good first issue
states the goal, the files involved, how to verify, and that help is available. Check that
each one is still open work before creating it.

Template for the body:

> **Goal:** …
> **Where:** …
> **Done when:** …
> **How to verify:** …
> New to Abada? Read CONTRIBUTING.md, comment here to claim the issue, and ask anything.

## `good first issue`

| # | Title | Labels | Where | Done when |
| --- | --- | --- | --- | --- |
| 1 | Add an invoice-approval example process | `examples` | `examples/apl/` | APL file + short README; deploys in Studio |
| 2 | Add an insurance-claims-triage example with a low-confidence route | `examples` | `examples/apl/` | Uses `on_low_confidence` to a human task |
| 3 | Add an HR leave-request example (no agent, pure rules + approval) | `examples` | `examples/apl/` | Shows Abada without AI |
| 4 | README for `examples/` listing every example and what it shows | `documentation` | `examples/README.md` | Table of examples with one-line descriptions |
| 5 | Troubleshooting entry: ports already in use on `up dev` | `documentation` | `documentation/src/content/docs/user/troubleshooting.mdx` | Symptom, cause, fix; `npm run build` passes |
| 6 | Troubleshooting entry: Docker Desktop memory too low | `documentation` | same | Includes the measured minimum |
| 7 | "Report a docs problem" link at the bottom of each docs page | `documentation` | `documentation/` (Starlight overrides) | Opens a pre-filled issue for the page |
| 8 | Glossary page (APL, agent node, result variable, lease, outbox…) | `documentation` | `documentation/src/content/docs/` | Linked from Concepts |
| 9 | Document Windows (WSL2) install steps | `documentation` | quickstart | Tested on WSL2 |
| 10 | Studio: empty-state hints on the tasks and instances lists | `studio` | `studio/src/` | Lint, tests, build pass |
| 11 | Studio: keyboard shortcut to save in the YAML editor | `studio` | `studio/src/` | Lint, tests, build pass |
| 12 | Document editor autocompletion for APL using the existing `apl-v1.schema.json` (VS Code YAML / JetBrains) | `documentation`, `tooling` | `engine/src/main/resources/apl/apl-v1.schema.json`, authoring guide | Steps tested; every file in `examples/apl/` validates |

## `help wanted` (larger)

| # | Title | Labels | Notes |
| --- | --- | --- | --- |
| 13 | Python worker client for external worker protocol v1 | `sdk`, `help wanted` | Mirror `sdk/java/`; fetch/lock, complete, fail, heartbeats; publish to PyPI later |
| 14 | TypeScript/Node worker client for protocol v1 | `sdk`, `help wanted` | Same scope as #13 |
| 15 | Go worker client for protocol v1 | `sdk`, `help wanted` | Same scope |
| 16 | Tested guide: running the agent worker against a local model (Ollama / vLLM OpenAI-compatible endpoint) | `documentation`, `help wanted` | Must be verified end to end |
| 17 | Helm chart derived from `k8s/` | `deployment`, `help wanted` | Kubernetes is not yet in the certified matrix — label as community-supported |
| 18 | VS Code extension: APL syntax, schema validation, deploy command | `tooling`, `help wanted` | Reuses `apl-v1.schema.json` |
| 19 | Example: a LangGraph (or other framework) agent as an external worker | `examples`, `help wanted` | Shows Abada governing an existing agent |
| 20 | Translations of the quickstart (French, Portuguese, Swahili…) | `documentation`, `help wanted` | Starlight i18n |
