# M4 — Governed improvement and launch: task specifications

- Milestone: M4 (2026-11-30 → 2026-12-18), release `1.1.0`
- Roadmap: [`roadmap.md`](roadmap.md)
- Audience: human contributors and coding agents. Execute one task per branch/PR.
  Read `AGENTS.md` first; its runtime invariants override anything here.
- Status: **draft for review.** Items marked *Verify first* rest on code I did
  not read while drafting; confirm them before the branch starts.

Every task lists **Goal**, **Files**, **Changes**, **Acceptance tests**,
**Invariants**, and **Out of scope**, as in [`m3-task-specs.md`](m3-task-specs.md).
`ENGINE` means `engine/src/main/java/com/abada/engine`, `ENGINE_TEST` means
`engine/src/test/java/com/abada/engine`, `WORKER` means
`agent-worker/src/main/java/io/abada/agent`, `SDK` means
`sdk/java/src/main/java/io/abada/worker`.

## Starting point

What exists at the end of M3 and what M4 changes:

- The Insight loop (V11, `ENGINE/insight/`) already exists and is disabled by
  default. It records terminal facts per node visit (`EXTERNAL_TASK`,
  `USER_TASK`, `DECISION`), analyses windows for failure rate, p95 latency and
  decision-fallback ratio, drafts a proposal (LLM or rule-based), and adopts it
  after policy-compliant human review (`InsightProposalService`). Adoption
  deploys the proposed source as a new version, and only if the targeted
  deployment is still the latest. M4 extends this loop; it does not add a second one.
- Facts have no agent dimension: no confidence, no link to the human who
  reviewed the agent's output, no cost. `insight_findings.signal` is
  constrained by a CHECK (V11), so new signals need a migration.
- A proposal is reviewed by reading a text diff. Nothing shows what the change
  would have done to real cases.
- Definitions are immutable and versioned (`process_definitions.version`,
  `findFirstByProjectIdAndProcessKeyOrderByVersionDesc`). Running instances are
  pinned to their deployment. There is no "go back" operation: the only way back
  is to hand-deploy an old source.
- Authoring (`AplAuthoringService`) asks a model for a whole YAML document using
  a hand-written system prompt that lists node types. The list is already stale
  (no `call-process`, no `delegates`) and `REFINE` rewrites the whole document.
  `GET /v1/apl/schema` and `POST /v1/apl/validate` (E1) already exist.
- Evidence and cost exist per step (`agent_steps`, V28/V30), per attempt
  (`external_tasks.attempt_cost_usd`), and a price table (`model_prices`).
- Call-process and delegation exist (E20); there is no fan-out over a list.
- The last migration in the tree is `V33__outbox_stream.sql`. Numbers below
  are provisional: take the next free number when the branch merges into `dev`.

## Order of work and dependencies

```
E14 signals ──► E15 replay ──► (policy: replay required) ──► exit demo
      │            ▲
      └── E17 authoring (patch mode feeds proposals, independent of E14/E15)
E16 rollback (independent, starts week 1)
E21 for-each (independent; reuses E20)
E18 site and video, E19 gate report: last
```

| Week | Work |
| --- | --- |
| 11-30 | E14 (facts, signals, `reviewed_by`); E16 rollback; E15 design spike and replay-instance flag |
| 12-07 | E15 replay run, diff, policy gate; E17 schema-driven authoring; E21 start |
| 12-14 | Studio for E14/E15/E16; E21 finish; exit demo; E18; E19 gate report |

Cut order (roadmap): E21, then E17, then the E18 Astro migration. E14 and E15
are never cut. E16 is small and carries the "one-click rollback" half of the
exit demo, so treat it as never cut too.

## Contract decisions taken in this plan

Change them here before implementation starts, not in a PR.

1. **Replay uses the real state machine.** A replay case is a real process
   instance flagged `replay_run_id`, executed by the same commands as any
   instance, against a *staged* copy of the proposed definition. There is no
   second interpreter, so replay cannot drift from runtime semantics.
2. **Replay has no external side effects.** Replay instances emit no outbox
   events, webhooks, messages or signals. Agent work for them is acquirable only
   by a worker that asks for replay work, which runs model calls and `read`
   tools only. `write` and `approval_required` tools are answered with a recorded
   `WOULD_CALL` result and never sent. Delegations replay the same way, one level
   at a time, with the same flag on the child.
3. **Replay shows what changes, not what is true.** The diff compares the
   proposed version's replayed path and outputs against what the original
   instance actually did. It is evidence for a reviewer, not a verdict. UI and
   docs must say so. Models are not deterministic, so a case is replayed once
   and the run records model, prompt hash and tokens.
4. **Human nodes are answered from history.** When a replayed case reaches a
   human node that the original instance also reached with the same task
   definition, the recorded outcome is replayed. Otherwise the case stops at
   `NEEDS_HUMAN` and is reported as such, never guessed.
5. **Replay is bounded and priced before it runs.** N is capped (default 20,
   hard cap 100), parallelism is capped, and a run has a `budget_usd`. An
   estimate from the price table is shown first. A run over budget stops and is
   reported partial, never silently complete.
6. **Staged versions are not startable.** A staged definition exists only to be
   replayed. It does not appear in process lists, cannot be started through
   `/v1/processes/start`, and is deleted with its run after retention. Adoption
   still deploys a fresh immutable version through the existing path.
7. **Rollback deploys, it does not mutate.** Rolling back to version V deploys
   V's exact source as a new version (`rollback_of = V`). History is append-only,
   running instances stay pinned to what they started on, and tool bindings and
   call targets are re-resolved at deploy time as for any deployment.
8. **Replay can be required by policy.** `insight_approval_policies` gains
   `replay_required` and `replay_min_cases`. When set, the final approval is
   refused until a completed replay exists for that proposal's exact checksum.
9. **Evidence stays redacted.** Replay stores digests, paths, outcomes and cost.
   Case payloads are read from the evidence the original instance already kept;
   cases whose payloads were purged are skipped and counted, never reconstructed.
10. **`for_each` ships on child processes only.** Per-item inline token bodies
    are deferred. Each item is an E20 child, which gives lineage, depth limits,
    cancel cascade and crash safety for free.

## E14 — Override, confidence-drift and cost signals ✱ never cut ✅ done

**Goal.** The Insight loop notices that people keep overriding an agent, that
its confidence is moving, or that it has become expensive, and says which node
and which human step it concerns.

**Files.** `engine/src/main/resources/db/migration/V34__agent_review_facts.sql`,
`ENGINE/insight/{InsightFactWriter,InsightAnalyzer,InsightProperties}.java`,
`ENGINE/persistence/entity/{InsightExecutionFactEntity,InsightFindingEntity}.java`,
`ENGINE/parser/AplParser.java`, `ENGINE/core/model/AgentWorkDescriptor.java`,
`ENGINE/apl/AplContractService.java`, `engine/src/main/resources/apl/apl-v1.schema.json`,
`studio/src/lib/apl/types.generated.ts`, `studio/src/features/insight/InsightPanel.tsx`,
`studio/src/features/designer/` (agent inspector field).

**Changes.**
- APL: an agent node may declare which human node reviews its output.
  ```yaml
  - id: triage
    type: agent
    result_variable: triage_result
    reviewed_by: { node: review, accept: approve }
    next: review
  - id: review
    type: human-input
    outcomes: { approve: { next: done }, reject: { next: triage, comment: required } }
  ```
  `reviewed_by.node` must exist, be a `human-input` with `outcomes`, and be
  reachable from the agent; `accept` must be one of its outcomes. Failures are
  `ABADA-APL-REVIEW-001` at the node path, in parser and in `POST /v1/apl/validate`.
  It is metadata only: it changes no routing.
- Facts: V34 adds `insight_review_facts` (visit id, definition key and
  deployment, agent node, review node, agent model, agent confidence 0–100 or
  null, outcome, `overridden`, attempt cost USD, cost unpriced flag, timestamps),
  one row per (agent visit, review visit) pair, written in the reviewer's
  completion transaction. `overridden` is `outcome != accept`. No payloads, no
  comments, no variable values. V34 also widens `ck_insight_findings_signal`.
  Per-visit agent cost is written for every agent visit, reviewed or not, so
  cost signals do not need `reviewed_by`.
- Signals added to `InsightAnalyzer`, all per (deployment, agent node), all
  needing a minimum sample size (`abada.insight.min-review-samples`, default 20):
  - `OVERRIDE_RATE`: overrides / reviews at or above a threshold (default 0.25).
  - `CONFIDENCE_DRIFT`: mean confidence of the window differs from the
    pre-window baseline by more than a threshold (default 10 points), computed
    from facts strictly before the window as for latency. A separate
    `CALIBRATION_GAP` is reported when the override rate among high-confidence
    outputs (≥ the node's `confidence_threshold`) exceeds the threshold, meaning
    confidence no longer predicts correctness.
  - `COST_PER_CASE`: mean agent cost per visit exceeds the pre-window mean by a
    factor (default 1.5). Unpriced visits are excluded and counted in the finding.
- Findings carry observed value, threshold, sample count, the agent node, the
  review node and the model(s) involved. The proposal generator receives them
  so a rule-based or LLM draft can target the right node (for example raise
  `confidence_threshold`, tighten a prompt, change a model). Generated candidates
  are still discarded unless `AplParser` accepts them.
- Studio: the Insight panel lists the new signals with the agent and review node
  and links to the node on the canvas; the agent inspector edits `reviewed_by`.

**Acceptance tests.**
- `AplReviewedByParserTest`: valid; unknown node, non-human node, human node
  without outcomes, unreachable node, `accept` not an outcome.
- `InsightReviewFactTest` (PostgreSQL): approve and reject through E5 outcomes
  write one fact each with the right `overridden`; rollback of the completion
  writes no fact; a rework loop writes one fact per review visit, not one per
  instance.
- `InsightAnalyzerAgentSignalsTest`: each signal fires above and not below its
  threshold; below `min-review-samples` nothing fires; the baseline excludes the
  window; unpriced visits excluded and counted; findings for different
  deployments are separate.
- `PostgresSchemaUpgradeTest` (V1–V33 → V34), `AplSchemaConformanceTest`,
  `OpenApiContractTest`, Studio `parser.test.ts` round trip of `reviewed_by`.

**Invariants.** Facts commit with the workflow transaction and roll back with
it. Facts hold identifiers, numbers and outcome names only; confidence is a
number already recorded by the engine, never read from a model response at
analysis time. Analysis stays out of the workflow transaction and the lease
singleton is unchanged.

**Out of scope.** Per-reviewer statistics (it would profile people; revisit
with a privacy review); alerting and notifications; automatic threshold tuning.

**As built (E14) ✅ done.** One table, `insight_agent_facts` (V34), holds the
agent visit and, once decided, its review (`review_node` is set at the visit
from the declaration, `reviewed_at`/`overridden` when the human node is
decided), rather than a separate review table. The pairing is the newest
unreviewed visit of that agent in the same instance, which pairs every pass of a
rework loop with its own review. Review signals are analysed in the window of
the decision (`reviewed_at`), visit signals in the window of the visit.
`ParsedProcessDefinition.getReviewOf/reviewsAt` carry the declaration; the
descriptor sent to workers is unchanged. Tests: `AplReviewedByParserTest`,
`InsightAnalyzerAgentSignalsTest`, `PostgresInsightReviewFactTest` (rework loop
pairs, a refused decision records nothing), schema and token upgrade tests
(→ V34), Studio `parser.test.ts` and `AgentEditors.test.tsx`.

**Deviations (E14).**
- `CALIBRATION_GAP` uses a global `abada.insight.high-confidence` (default 85)
  rather than each node's `confidence_threshold`: the analyzer reads facts, not
  definitions.
- Studio edits `reviewed_by` in the agent inspector; the Insight panel lists
  the new signals through the existing findings view, with no new screen.
- The `OpenApiContractTest` is unaffected (no endpoint changed).

## E15 — Replay before approval ✱ never cut

**Goal.** A reviewer sees what a proposed change would have done to the last N
real cases, and approves or rejects from that diff. A policy can make the replay
mandatory.

*Verify first.* (a) Which original inputs are recoverable for a finished
instance: start variables and agent inputs from history and `agent_steps`
evidence, and what the redaction policy has already purged. (b) How
`engine.deploy` and the definition listing queries would hide a `STAGED`
version. If (a) is not enough, E15 starts by recording start variables as
evidence at instance start (additive, behind the existing redaction policy) and
the first usable replay corpus begins after that release; say so in the release
notes rather than faking history.

**Files.** `engine/src/main/resources/db/migration/V35__replay.sql`,
`ENGINE/replay/{ReplayService,ReplayCaseSelector,ReplayDiffService,ReplayProperties}.java` (new),
`ENGINE/core/AbadaEngine.java` (replay flag handling),
`ENGINE/persistence/entity/{ProcessDefinitionEntity,ProcessInstanceEntity}.java`,
`ENGINE/insight/{InsightProposalService,InsightProposalEntity,InsightApprovalPolicyEntity}.java`,
`ENGINE/api/{ProjectInsightController,InsightController,WorkerController}.java`,
`ENGINE/security/` (permission), `SDK/AbadaWorkerClient.java`,
`WORKER/loop/AgentLoop.java`, `studio/src/features/insight/`.

**Changes.**
- V35: `replay_runs` (id, project, proposal id, proposal checksum, base
  deployment, staged deployment, requested N, status `PLANNED | RUNNING |
  COMPLETED | PARTIAL | FAILED | CANCELLED`, budget, cost so far, requested by,
  timestamps), `replay_cases` (run, original instance id, original path digest,
  replay instance id, state `PENDING | RUNNING | DONE | NEEDS_HUMAN | SKIPPED
  | FAILED`, skip reason, per-case cost, diff JSON), `process_definitions.status`
  (`ACTIVE` default, `STAGED`), `process_instances.replay_run_id`. Indexes on run
  and on `(status)` for the definition listing filter.
- Selection: the last N completed instances of the proposal's *target*
  deployment (N from the request, default 20, caps in `ReplayProperties`),
  newest first, one case per instance. Cases with purged inputs are `SKIPPED`
  with reason `PAYLOAD_PURGED`. Nothing is selected from instances that are
  still running. If fewer than N exist the run reports how many.
- Start: `POST /v1/projects/{projectId}/insight/proposals/{id}/replays` with
  `{ cases, budgetUsd }`. It validates the proposal is `DRAFT | IN_REVIEW`,
  stages the proposed source (parser-validated, tool bindings and call targets
  resolved exactly as at deploy; a source that does not resolve is refused
  with the same typed errors), returns the cost estimate and creates the run
  `PLANNED`. `POST .../replays/{runId}/start` confirms. Two steps so the
  estimate is seen before money is spent. Only one active run per proposal.
- Execution: a durable job per case (same acquisition and lease rules as other
  jobs) starts a replay instance from the original start variables through the
  normal start command with `replay_run_id` set, at most `max-parallel` at once
  (default 2). Decision 2 defines the isolation. Human nodes follow decision 4.
  Child processes started by `call-process` or delegation inherit the flag.
  Timers inside replay instances are collapsed (they fire immediately) and the
  diff notes it; replay does not wait real time.
- Worker: replay agent work is a separate acquisition filter (`replay=true`).
  The SDK/worker run the model call and `read` tools only. For any other tool
  the worker journals a step whose result is `WOULD_CALL` with the argument
  digest; the engine rule that refuses a non-read tool for replay work is
  enforced engine-side, not trusted to the worker.
- Diff, per case and in aggregate (`ReplayDiffService`, pure and unit-tested):
  nodes visited (added / removed / reordered), terminal end node, routing
  decisions (condition and `routes`), agent confidence (original vs replay),
  predicted human outcome where recorded, `WOULD_CALL` list, cost delta. An
  aggregate reports changed-path count, end-node changes, and, when the
  proposal addresses an `OVERRIDE_RATE` finding, how many originally
  overridden cases would now route to the human differently. Wording is
  "would have", never "is better".
- Gate: `insight_approval_policies` gains `replay_required` and
  `replay_min_cases` (default off, 10). With the policy on, a review that would
  adopt is refused with typed `409 REPLAY_REQUIRED` unless a `COMPLETED` run
  exists for the proposal's current checksum with at least `replay_min_cases`
  non-skipped cases. A rejection is never blocked. Editing the proposal changes
  the checksum and invalidates the run.
- Cleanup: staged versions and replay instances are deleted `retention_days`
  after the run (reuse the evidence retention setting); a cancelled run cancels
  its pending jobs and instances inline.
- Permissions: starting a replay needs `insight:review`; reading needs
  `insight:read`. Audit history records `REPLAY_REQUESTED`, `REPLAY_COMPLETED`
  with actor, proposal, case count and cost.
- Studio: on the proposal page, a Replay tab: estimate and start, progress,
  an aggregate summary, a per-case list with the path diff on the canvas
  (original path vs replay path), `NEEDS_HUMAN` and `SKIPPED` shown plainly,
  and the review buttons beside the diff so approval happens from it.

**Acceptance tests.**
- `ReplayDiffServiceTest`: identical paths; added, removed and reordered nodes;
  changed end node; changed routing; `WOULD_CALL` listing; cost delta; unpriced
  cases excluded and counted.
- `PostgresReplayIsolationTest`: a replay of a process with a write tool, a
  message throw, a signal throw and a webhook sends nothing: the stub tool
  server counter stays 0, the outbox has no rows for replay instances, no
  event reaches the stream; the original instances are unchanged.
- `PostgresReplayRunTest`: N=20 over a seeded corpus completes and the diff is
  as expected; fewer than N finished instances; purged payload skipped;
  budget exceeded → `PARTIAL`; case reaching a new human node → `NEEDS_HUMAN`;
  cancel mid-run leaves no live replay instance.
- `PostgresReplayCrashTest`: kill the engine mid-run; the run resumes, every
  case is replayed once (one replay instance per case), no duplicate model
  call is recorded for a leased case.
- `ReplayPolicyGateTest`: `replay_required` blocks adoption without a run,
  with a stale-checksum run, and with too few cases; allows it with a
  qualifying run; never blocks rejection.
- `ReplayWorkerFilterTest`: a normal worker never receives replay work and a
  replay worker never receives normal work; the engine refuses a non-read tool
  step on replay work even if the worker sends it.
- `ReplayAuthorizationTest`: roles, cross-project access and cross-proposal
  run ids rejected; staged definitions invisible in `GET /v1/processes` and
  not startable.
- `PostgresSchemaUpgradeTest` (→ V35), `OpenApiContractTest`, Studio
  component tests for the Replay tab.

**Invariants.** A replay never writes to an original instance and never
reaches a system outside the engine and the model provider. The model runs
outside the transaction as always. The staged version is parsed and resolved by
the same code as a real deployment. Locks follow the existing parent-before-child
order.

**Out of scope.** Replaying against live traffic or shadow mode; statistical
significance testing; replaying BPMN-only definitions with no agent (they are
allowed, but no agent-specific diff is promised); automatic adoption from a good
replay (decision: humans own change).

## E16 — One-click rollback ✱ never cut

**Goal.** An authorised person can return a process to a prior version with one
action, and the history says who did it, from what and why.

**Files.** `engine/src/main/resources/db/migration/V36__definition_rollback.sql`,
`ENGINE/core/AbadaEngine.java` (deploy path), `ENGINE/project/ProjectDocumentService.java`,
`ENGINE/api/{ProjectProcessController,ProcessController}.java`,
`ENGINE/insight/InsightProposalService.java`, `ENGINE/security/`,
`studio/src/features/workspace/` and `studio/src/features/insight/`.

**Changes.**
- V36: `process_definitions.rollback_of` (nullable version), `deployed_by`,
  `deploy_comment` if not already stored. Verify first which of these exist.
- `GET /v1/projects/{projectId}/processes/{key}/versions` lists versions with
  checksum, deployer, timestamps, whether it came from an Insight proposal, a
  rollback or a plain deploy, and the count of running instances on each.
  `GET .../versions/{a}/diff/{b}` returns a unified source diff.
- `POST .../versions/{version}/rollback` with a required `comment` and
  optional `expectedLatest` (the version the caller saw as current; stale → typed
  409). The service loads version V's stored source, deploys it as version N+1
  through the normal deploy path (decision 7), sets `rollback_of = V`, records
  `DEFINITION_ROLLED_BACK` (actor, from N, to V, new N+1, comment, trace id).
- Dependency check before deploying: tool references and call targets in V's
  source are re-resolved against the current registry. If they no longer
  resolve, the request is refused with the same typed `ABADA-APL-TOOL-002` /
  call-target errors and a message saying which dependency changed. Nothing is
  half-applied.
- Open proposals on the process are marked `SUPERSEDED` (their target is no
  longer latest), as adoption already does.
- Running instances keep their pinned deployment. The response says how many
  were started on the version being replaced and that they are unaffected.
- Permission `process:rollback` (Operator and Admin by default). Policy
  option on the project: `rollback_requires_second_approver`, off by default;
  when on, rollback creates an approval task like E10 instead of deploying.
- Studio: a Versions drawer on the process (list, diff against current, a
  Roll back button with a comment field and the running-instances count). The
  Insight proposal page links to the version it adopted and offers rollback of
  that adoption in one click.

**Acceptance tests.**
- `PostgresRollbackTest`: deploy v1, v2, roll back to v1 → v3 has v1's source
  and checksum, `rollback_of = 1`; a running v2 instance finishes on v2; new
  starts use v3; rollback of a rollback works; history entries are correct.
- `RollbackDependencyTest`: a removed tool or changed child makes rollback
  refuse with typed errors and writes nothing.
- `RollbackConcurrencyTest`: two simultaneous rollbacks and a concurrent
  deploy produce distinct monotonic versions; stale `expectedLatest` → 409.
- `RollbackAuthorizationTest`: role boundaries, cross-project, missing comment;
  second-approver mode creates a task and deploys only after approval.
- Insight interaction: rollback supersedes an open proposal; adopted-proposal
  rollback links both ways.
- `PostgresSchemaUpgradeTest` (→ V36), `OpenApiContractTest`.

**Invariants.** Versions are never edited or deleted. Rollback is a deploy:
same validation, same immutability, same pinning. Audit is append-only.

**Out of scope.** Rolling back running instances to an earlier version;
database or data rollback; bulk rollback across processes.

## E17 — Schema-driven natural-language authoring with patch-mode refine

**Goal.** Natural-language authoring always knows the current language, and a
refinement changes only what was asked, shown as a reviewable patch.

**Files.** `ENGINE/authoring/{AplAuthoringService,AplPromptBuilder,AplPatchService,AplPatchOp}.java`,
`ENGINE/api/ProjectAuthoringController.java`, `ENGINE/apl/AplContractService.java`,
`ENGINE/llm/OpenAiCompatibleLlmClient.java`, `engine/src/test/resources/authoring/` (golden prompts),
`studio/src/features/ai/`, `studio/src/features/designer/`.

**Changes.**
- Prompt from schema: `AplPromptBuilder` builds the system prompt from
  `apl-v1.schema.json` (node types, fields, enums, bounds) and the
  `apl-node-reference` summary, so it cannot drift again. A unit test fails if a
  node type in the schema is missing from the prompt.
- Create mode keeps the full-document path, with the repair loop fed by the
  structured issues from `POST /v1/apl/validate` (paths, codes) rather than one
  exception message. Max repairs stays bounded.
- Refine mode becomes patch-mode: the model returns a JSON list of operations
  (`addNode`, `removeNode`, `updateNode` with a field path and value,
  `setEntry`, `relink`), never the whole file. `AplPatchService` applies them to
  the parsed document, re-serialises, validates, and rejects a patch that
  touches anything not named in an operation. Node ids not in the patch are
  guaranteed unchanged; a patch that renames an id is refused unless it is an
  explicit `renameNode` and every reference is updated by the service.
- Response: `{ aplSource, patch, summary, provider, model, attempts, warnings,
  validation }`. The source is returned for review only, as today; this service
  never persists workflow state and never deploys.
- The local deterministic fallback stays and still says it is a starter. Refine
  with no configured model returns a typed error instead of a pretend change.
- Studio: show the patch as a node-level diff on the canvas (added / changed /
  removed) and as a YAML diff; accept loads it into the editor; reject discards.
  Undo/redo covers an accepted patch as one step.
- Proposals: `InsightProposalGenerator` uses patch mode so a proposal diff is as
  small as the finding that caused it.

**Acceptance tests.**
- `AplPromptBuilderTest`: every schema node type and its required fields appear;
  adding a node type to the schema without touching the builder still works.
- `AplPatchServiceTest`: each operation; unknown node; dangling reference; id
  rename with reference rewrite; a patch touching an unnamed node is refused;
  the result always passes `AplParser`.
- `AplAuthoringGoldenTest`: a stub model replays recorded responses for a set of
  twenty prompts (create and refine, including M2/M3 node types). Offline,
  deterministic, runs in CI; a separate opt-in test can call a real provider.
- `AplAuthoringRepairTest`: an invalid first candidate is repaired using the
  structured issue; after the bound it falls back and says so.
- `OpenApiContractTest` for the response shape; Studio component test for the
  patch view.

**Invariants.** Authoring output is a candidate for human review. It is never
deployed or persisted by this path, and it carries no secrets: the prompt holds
the schema and the user's request and, in refine, the source the user already has.

**Out of scope.** Multi-turn chat memory; authoring decision tables from
natural language; generating tool server documents; any auto-apply.

## E21 — Bounded for-each

**Goal.** A process can run the same governed child process over a list, with
hard bounds, and gather the results without one bad item failing the rest.

**Files.** `engine/src/main/resources/db/migration/V37__for_each.sql`,
`ENGINE/parser/AplParser.java`, `ENGINE/core/AbadaEngine.java`,
`ENGINE/core/ForEachService.java` (new), `ENGINE/persistence/entity/ForEachItemEntity.java`,
`ENGINE/apl/AplContractService.java`, `engine/src/main/resources/apl/apl-v1.schema.json`,
`studio/src/lib/apl/`, `studio/src/features/designer/`, `studio/src/features/operations/`.

**Changes.**
- APL node `for-each`:
  ```yaml
  - id: check_each
    type: for-each
    items: ${documents}            # CEL; must evaluate to a list
    process: kyc-document-check    # E20 child, pinned at deployment
    item_input: document           # child variable receiving the item
    inputs: { case_id: ${case_id} }
    outputs: { verdicts: verdict } # child output gathered per item
    max_items: 50                  # required; hard engine cap
    max_parallel: 5                # default 1; hard engine cap
    results: check_results
    on_error: manual_review        # taken once, after all items settle
    next: decide
  ```
  `max_items` is required and capped by `abada.for-each.max-items` (default
  100). A list longer than `max_items` fails the node with `FOR_EACH_TOO_MANY`
  before any child starts. The target is pinned and type-checked as in E20a.
- V37: `for_each_items` (parent token, item index, state `PENDING | RUNNING |
  DONE | FAILED | CANCELLED`, child instance id, gathered output JSON, error
  code) unique on `(parent_token, item_index)`.
- Execution: the node's token parks `WAITING`; in the same transaction the
  items are materialised as `PENDING` rows. A durable dispatch job starts
  children while fewer than `max_parallel` are `RUNNING`, in index order,
  parent locked before child. A child's end reuses the `CHILD_DONE` path and
  marks its row. When no row is `PENDING` or `RUNNING`, a completion job gathers
  `results` as a list in item order, each entry `{ index, ok, outputs | error }`,
  and moves the token to `next`, or to `on_error` (or opens an incident) if any
  item failed. Other items always run to completion first.
- Cancel and timeout cascade to all running children inline as in E20a; pending
  rows become `CANCELLED`.
- Agents are not given `for-each`; it is deterministic engine control flow.
- History: `FOR_EACH_STARTED`, `FOR_EACH_ITEM_DONE`, `FOR_EACH_COMPLETED` with
  counts, never item values. Lineage lists each child. Studio draws the node and
  shows "n of m done, k failed" on the instance page.

**Acceptance tests.**
- `AplForEachParserTest`: missing `max_items`, over-cap, unknown process,
  non-list `items` caught statically where possible, unknown `item_input`.
- `PostgresForEachTest`: 12 items with `max_parallel: 3` never exceed 3 running;
  results in item order; one failing item routes to `on_error` after the other
  11 completed; empty list passes straight to `next` with an empty result;
  over-limit list fails before starting any child; cancel stops pending and
  running items; timeout cancels children.
- `PostgresForEachCrashTest`: kill the engine with items in each state; every
  item has exactly one child, no orphan, results gathered once; two replicas
  dispatching at once never exceed `max_parallel`.
- `PostgresSchemaUpgradeTest` (→ V37), `OpenApiContractTest`,
  `AplSchemaConformanceTest`, Studio round trip.

**Invariants.** Items are durable rows, not in-memory loops. The bound is
enforced by the engine on every dispatch, not by the author. Lock order is
parent, then child.

**Out of scope.** Inline per-item bodies (decision 10); nested for-each beyond
the E20 depth limit; streaming results; reduce or aggregate functions beyond
gathering.

## E18 — Static site, `/investors`, 3-minute video

**Goal.** The public face says only what the code and tests prove, shows the
governed-improvement story, and has a page for investors.

*Verify first.* The roadmap cut line mentions an "Astro migration"; `abada-site/`
is currently a Bun/Turborepo project and `documentation/` is already Astro.
Decide before starting whether the site moves to Astro or stays. The migration
is the first thing cut; the claims work below is not.

**Changes.**
- A claims register (`abada-site/claims.md` or a data file): each public claim
  has a pointer to the test, the gate report or the doc that proves it. A script
  fails the site build when a claim has no pointer or the pointer is missing.
- Update product copy for 1.1.0: agents with tools and approvals, delegation,
  rework loops, replay-backed proposals, rollback. Drop anything the release does
  not do (no "auto-improving", no "exactly-once" for side effects).
- `/investors`: problem, the five doctrine statements, what exists today
  (linked to gate reports), the target users, the open-source and licence
  model (AGPL-3.0, SDK Apache-2.0), the roadmap pointer, a contact. No
  customer names or numbers unless a partner approved them in writing.
- Video (≤ 3 minutes): script and storyboard committed under
  `docs/assets/video/`, recorded from the exit demo run on a real stack with the
  screenshot capture setup already used for the docs. Captions in the repo.
  Recording itself is a human task.
- Performance and accessibility pass; no third-party fonts or trackers
  (consistent with rc.7).

**Acceptance tests.**
- Claims check script runs in CI and in the site build.
- Link check passes; Lighthouse accessibility ≥ 95 on `/` and `/investors`.
- A reviewer who is not the author walks every claim to its proof.

**Out of scope.** Pricing pages, customer logos, SaaS sign-up, blog.

## E19 — 1.1.0 gate report, upgrade notes, pilot runbook

**Goal.** 1.1.0 is published with evidence, and a design partner can run a
pilot from the repository alone.

**Changes.**
- Version bump to `1.1.0` everywhere AGENTS.md requires (Maven, OpenAPI,
  observability, Compose tags, README, roadmap gate, release notes).
- Promote `ABADA-APL-TOOL-001` (bare tool names) from warning to error, as
  promised in M3; add a migration note and the Studio quick fix.
- `docs/release-notes/1.1.0.md` with the V34–V37 upgrade path, new permissions
  (`process:rollback`, replay), policy options, limits (replay is evidence, not
  a verdict; for-each on child processes only; at-least-once external effects)
  and the unproven items stated as such.
- `docs/development/1.1-gate-report-<date>.md` under
  `1.0-rc-publication-gates.md`: full backend, Studio, documentation, Compose
  and image checks, JAR SHA-256, upgrade tests from every supported schema, and
  the exit demo evidence.
- `docs/operations/pilot-runbook.md`: prerequisites, topology, deploying a first
  process, turning on Insight and `reviewed_by`, a replay-required policy, a
  rollback drill, backup and restore, what to monitor, and who to call.
- Reference docs and the Starlight guide updated in the same PRs as E14–E21
  (see cross-cutting work).

**Acceptance.** Per the publication gates; GO/NO-GO signed off by the owner.

## Cross-cutting work

- **Documentation** in the same PRs: `insight-loop.md` (new signals, replay,
  policy), `apl-specification.md` and `apl-node-reference.md` (`reviewed_by`,
  `for-each`), `runtime-semantics.md` (replay isolation, rollback, for-each
  lifecycle), `api-v1.md` and OpenAPI (versions, rollback, replays, authoring
  patch response), `runtime-state.md` (new tables, lock order, staged
  definitions), `security-and-rbac.md` (new permissions), and the Starlight guide.
- **Examples:** `examples/apl/m4-triage-with-review.yaml` (agent with
  `reviewed_by` and a seeded override pattern), `examples/apl/m4-document-batch.yaml`
  with its child, and a seed script under `scripts/dev/` that generates a
  corpus of finished instances for the demo and tests.
- **Protocol:** replay acquisition and the `WOULD_CALL` step result are additive
  v1 fields; the SDK keeps ignoring unknown fields. Update
  `external-worker-protocol-v1.md`.
- **Migrations:** each one needs a fresh-database test and upgrade tests from
  every supported prior version, per AGENTS.md.

## Risks

| Risk | Mitigation |
| --- | --- |
| Original inputs are not recoverable for finished instances, so there is nothing to replay | The *Verify first* check on day one; if needed, record start variables as evidence now and state the limitation in the release notes |
| Replay leaks a side effect | Decision 2 enforced engine-side, with an isolation test that counts calls on a stub tool server |
| Replay cost surprises an operator | Estimate before start, `budget_usd`, `PARTIAL` status |
| Model non-determinism makes a diff look like a regression | One replay per case with model and prompt hash recorded; copy says "would have"; no statistical claims |
| E15 is larger than three weeks | Cut E21 and E17 first, per the roadmap; if still short, ship replay without the aggregate override analysis and keep path diff and the policy gate |
| Staged versions leak into listings or starts | Filter at the repository layer, with the authorization test above |

## Exit demo (1.1.0)

On the PostgreSQL topology, with the seeded corpus of at least 40 finished
instances of `m4-triage-with-review`:

1. Insight shows an `OVERRIDE_RATE` finding on the triage agent, naming the
   agent node and its `review` node, with sample count and threshold.
2. The generated proposal (patch-mode) changes the prompt and raises
   `confidence_threshold`. The policy requires a replay.
3. Approval is refused (`REPLAY_REQUIRED`). The reviewer starts a replay on the
   last 20 cases, sees the cost estimate, confirms, and an engine replica is
   killed mid-run; the run still finishes, every case replayed once, the stub
   tool server's write counter is 0.
4. The reviewer reads the diff (changed paths, cases that would now go to a
   human, cost delta) and approves from it. The new version is deployed.
5. The reviewer rolls back in one click with a comment. A new version holding
   the previous source is deployed, instances started before it are untouched,
   and audit shows proposal, replay, approval and rollback with actors.

Evidence goes in `1.1-gate-report-<date>.md` under the publication gates.
