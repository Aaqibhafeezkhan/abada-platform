# Insight Loop reference

The Insight Loop observes terminal execution facts in PostgreSQL and produces
human-governed APL proposals. It is disabled by default and never changes a
running process instance.

## Durable data model

| Table | Purpose |
| --- | --- |
| `insight_execution_facts` | One idempotent row per node visit (`visit_id`), with identifiers, terminal status, timing, and non-PII decision metadata. |
| `insight_observation_windows` | Durable analyzer cursor and `ANALYZED` / `COMPLETED` / `FAILED` recovery state. |
| `insight_agent_facts` | One row per successful agent visit: model, the confidence the engine recorded, priced cost and, when the node declares `reviewed_by`, the reviewer's outcome and whether it overrode the agent. Identifiers, numbers and outcome names only. |
| `insight_findings` | Threshold violations for one deployment and node. |
| `insight_proposals` | Full target/proposed APL, checksum, policy snapshot, lifecycle, and adopted version. |
| `insight_approval_policies` | Versioned policy per definition key. |
| `insight_proposal_reviews` | Immutable actor decision and comment; one row per actor/proposal. |
| `insight_worker_lease` | Cluster-safe singleton analysis lease. |

Facts and workflow state commit or roll back together. The analyzer persists
its window and findings first, performs optional LLM generation without a
database transaction, then completes the window. An `ANALYZED` window left by
a crash is resumed after the lease expires.

Latency baselines use facts strictly before the current window. Facts in the
window cannot train their own baseline. Repeated visits to one activity are
separate facts because uniqueness is based on the visit/task/audit ID, not the
activity ID.

## Signals

- external-task failure rate after `min-attempts`;
- external-task p95 latency relative to the pre-window p95 baseline;
- decision-table fallback ratio after `min-fallback-samples`.

### Agent signals

An agent node may declare `reviewed_by: { node, accept }` (see the
[node reference](apl-node-reference.md)). The reviewer's decision is recorded
in the transaction that completes the review, so a refused decision records
nothing and every pass of a rework loop pairs with its own review. An outcome
other than `accept` is an override. Per agent node and deployment, each signal
needs `abada.insight.min-review-samples` (default 20) samples:

- `OVERRIDE_RATE`: overrides / reviews in the window at or above
  `override-rate-threshold` (default 0.25). Reviews fall in the window of the
  decision, not of the agent's visit.
- `CALIBRATION_GAP`: the same rate among outputs whose confidence is at least
  `high-confidence` (default 85), meaning confidence no longer predicts
  correctness.
- `CONFIDENCE_DRIFT`: the window's mean confidence differs from the mean of
  earlier facts by `confidence-drift-points` (default 10) or more.
- `COST_PER_CASE`: mean priced cost per visit is at least `cost-factor`
  (default 1.5) times the earlier mean. Unpriced visits are left out.

Baselines use facts strictly before the window. There is no per-reviewer
statistic: signals describe the agent, never the person.

The rule-based fallback proposal preserves executable semantics and annotates
the source. A configured OpenAI-compatible generator may draft a real change,
but the candidate is discarded unless `AplParser` accepts it and its process
key matches the target.

## Proposal lifecycle

`DRAFT → IN_REVIEW → ADOPTED | REJECTED | SUPERSEDED`

- `ADOPTED`: the policy is satisfied and the target deployment/checksum is
  still latest; a new native APL version was deployed.
- `REJECTED`: any authorized reviewer rejected with a non-empty comment.
- `SUPERSEDED`: the targeted definition changed before final approval.

There is at most one `DRAFT`/`IN_REVIEW` proposal per deployment. Terminal
proposals remain available for audit.

## Replay before approval

A reviewer can replay a proposal on the last recorded cases of the deployment
it targets, and an approval policy can require it. A replay is **evidence, not
a verdict**: it shows what the proposed definition would have done on the same
inputs, never that it is better.

- **Cases.** The newest finished root instances of the target deployment whose
  start inputs were kept (`process_start_inputs`, governed by the project's
  evidence policy: absent under `none`, masked under `redacted`, cleared after
  retention). Finished cases without usable inputs are counted
  (`withoutInputs`) and never reconstructed. Instances started before 1.1.0
  have no inputs.
- **Same interpreter.** The proposed source is parsed and walked in memory by
  the engine's own `ProcessInstance`: conditions, decision tables, gateways,
  loops, boundaries and agent output contracts behave as at runtime. No
  instance, task, job, subscription or outbox row is created; only
  `replay_runs` and `replay_cases` are written, plus audit events for the
  replay itself (`REPLAY_PLANNED`, `REPLAY_STARTED`, `REPLAY_COMPLETED`,
  `REPLAY_CANCELLED`).
- **Agent steps** are answered by one model call per step, made by the engine
  outside any transaction through the provider configured for the node's model,
  priced from the engine's price table and checked against the node's output
  contract. **Tools are not run** (the engine never calls a tool server); a
  node with tools is reported as `toolsSkipped`. Fallback models, temperature
  and token limits of the node are not applied.
- **Reviewer steps** are answered from what the original reviewer decided. A
  step with no recorded decision, or a recorded outcome the proposal removed,
  stops the case as `NEEDS_HUMAN`; an engine task, timer, message or called
  process stops it as `NEEDS_EXTERNAL`. A reviewer's comment is never kept: a
  rework prompt that reads it sees `[reviewer comment not replayed]`.
- **Diff.** The path of decisions (agent outcomes, routes, reviewer outcomes,
  decision-table rules) of the original, from its history, against the
  replayed path: first divergence, changed outcomes, steps only one path took.
  The aggregate counts changed and unchanged cases and, for proposals that
  target an agent with `reviewed_by`, how many originally overruled cases would
  now take a different path.
- **Bounds and cost.** At most `abada.replay.max-cases` (100) cases, default
  20, `abada.replay.max-parallel` (2) at once. Planning prices the run from what
  the selected cases cost originally; nothing is spent until it is started. A
  budget stops the run `PARTIAL` and skips the rest.
- **Crash safety.** A case is leased when claimed and settled once; a replica
  that dies leaves it to be claimed again after the lease, so a model call may
  repeat but a case is never lost or recorded twice.
- **Policy.** `replayRequired` and `replayMinCases` on the approval policy
  refuse every approval (`409 REPLAY_REQUIRED`) until a `COMPLETED` run exists
  for the proposal's current source with at least that many cases replayed.
  Editing the proposal invalidates earlier runs. A rejection is never blocked.

## API and authorization

| Operation | Authority |
| --- | --- |
| Read configuration, policies, proposals | `insight:read`, Insight Reviewer, Operator, or Admin |
| Approve/reject | `insight:review`, Insight Reviewer, or Admin |
| Update policies | `insight:configure` or Admin |
| Plan, start, cancel a replay | `insight:review`, Insight Reviewer, or Admin, and a project `REVIEWER`, `MAINTAINER` or `OWNER` |
| Read replays | as proposals |

Endpoints are under `/api/v1/insight`. Proposal lists are paginated. Review
requests accept `expectedUpdatedAt`; stale requests return a typed 409.
Policies use `expectedVersion`. `requiredApprovals` must equal the number of
distinct comma-separated reviewer groups. Parallel policies accept those
groups in any order; sequential policies enforce their declared order.

## Configuration

The principal setting is `ABADA_INSIGHT_ENABLED`. Insight calls the model
through the AI provider marked *Used by Insight* in Studio > Settings > AI
Providers, with the same keys that serve agent tasks; without one it falls
back to the engine's `ABADA_LLM_*` environment provider (see
[AI providers](ai-providers.md)). The LLM connection is shared with
project-scoped APL authoring, but `ABADA_INSIGHT_ENABLED` controls only the
scheduled Insight worker: Studio authoring remains available while that worker
is disabled. Thresholds and scheduling are available under `abada.insight.*`.
Secrets are accepted as input only; the configuration API exposes whether a
key is present, never its value.

OpenTelemetry remains optional diagnostic output and is not an authoritative
input to this v1 loop. See ADR-003.
