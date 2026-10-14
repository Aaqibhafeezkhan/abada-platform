# Pilot runbook

How a design partner runs one governed AI-driven process on their own
infrastructure, from an empty server to a first replay-backed change. It
assumes the 1.1.0 release; every command is one the release already documents.
Nothing here needs an account with us.

## 0. What you need

- One Linux host or VM with Docker Engine and Compose v2 (a single-VM
  profile is described in [Docker deployment](docker-deployment.md#server-single-vm)).
- PostgreSQL, which the bundled composition provides. It is the only required
  infrastructure; production uses your OIDC provider for sign-in.
- An AI provider key for the model your process uses. Keys are entered in
  Studio under **Settings → AI Providers**, stored encrypted, and never shown
  again.
- A process you can describe as: *an agent drafts or decides, rules route, a
  person approves*. Start with one that people already review today.

## 1. Install and verify

1. Download the release archive and verify its published SHA-256 file.
2. Follow [Docker deployment](docker-deployment.md) for the `server` profile
   (or `prod` with your own OIDC provider). `ABADA_ENCRYPTION_KEY` must be set
   and kept with your database backups: stored provider keys, tool credentials
   and evidence payloads cannot be read without it.
3. Run `./release/abada-platform doctor prod --env-file .env.prod` and fix
   what it reports before starting.
4. Check `/api/actuator/health`, then sign in to Studio.

## 2. Model your process

1. Create a project and add its members: *owner* and *maintainer* author,
   *operator* runs and retries, *reviewer* approves changes, *viewer* reads.
2. Write the process in APL in Studio (or describe it and let the authoring
   assistant draft it; the draft is a candidate you review, never applied on
   its own). Keep agents probabilistic and rules deterministic:
   - an `agent` with an `output_schema`, a `confidence_threshold` and
     `on_low_confidence` / `on_invalid_output` routes to a person;
   - `human-input` steps with `outcomes: { approve, reject }` where rejection
     needs a comment;
   - bounded loops for rework, and `sla_hours` where timing matters.
3. Tell the Insight loop which review checks which agent:
   `reviewed_by: { node: review, accept: approve }` on the agent. Any other
   outcome then counts as an override.
4. Use **Dry Run**, then deploy. Deployment checks everything the engine will
   enforce; fix what it reports.

## 3. Run it for real

- Start instances from your system with the REST API
  (`POST /api/v1/projects/{id}/processes/{key}/start`) or from Studio.
- Operators watch **Operations**: instances, open incidents (retry, or retry an
  agent on another allowed model), worker health and the cost of each
  instance.
- Decide the **evidence policy** for the project (Settings): `redacted` is the
  default (secrets and sensitive variables masked), `none` keeps no payloads,
  `full` keeps them as sent. Payloads are encrypted and cleared after the
  retention period. This also decides what a later replay can use: start inputs
  are kept under the same policy, so a project on `none` cannot be replayed.

## 4. Let the process improve, with people in charge

1. Turn the Insight loop on (`ABADA_INSIGHT_ENABLED=true`) and choose the AI
   provider it uses in **Settings → AI Providers**.
2. After enough cases (the default needs 20 reviewed outputs per signal), it
   reports, per agent step: how often reviewers overrule the agent, whether its
   confidence is drifting, and whether it costs more than it did.
3. A finding becomes a **proposal**: a small change to the process, shown as a
   diff. Nothing applies by itself.
4. Before anyone approves it, **replay** it: Studio plans a replay on the last
   recorded cases, shows the estimated cost, and runs only when you start it.
   You see which cases would have gone differently and where. A replay is
   evidence, not a verdict. Tools are not run in a replay, reviewer comments
   are not kept, and cases that need a worker, a timer or a called process
   stop where they would wait.
5. To make this mandatory, set `replayRequired` (and `replayMinCases`) on the
   approval policy of the process with
   `PUT /api/v1/projects/{id}/insight/policies/{key}`; Studio does not have a
   switch for it yet. Approvals are then refused (`409 REPLAY_REQUIRED`) until
   a finished replay of the proposal's current source exists.
6. Approve or reject under the policy. An approval deploys a new immutable
   version; running instances finish on the version they started on.

## 5. Go back

Open **Operations → Versions and rollback**, choose the earlier version, read
its diff against the current one, give a reason and roll back. It deploys the
old source as the next version, so history stays complete and nothing running
is touched. A rollback is refused, and changes nothing, if a tool or called
process it depends on has since changed; the message names which.

## 6. Operate it

- **Back up** PostgreSQL before every upgrade and test a restore
  (`./scripts/test/verify-backup-restore.sh` shows the drill). Keep
  `ABADA_ENCRYPTION_KEY` with the backup.
- **Upgrade** by [the procedure](upgrading.md): back up, stop replicas across a
  schema change, change `ABADA_VERSION`, run `doctor`, start one replica, then
  the rest.
- **Watch** the failed-jobs list, open incidents and the worker health panel;
  optional telemetry is described in [Observability](observability.md).
- **Know the limits**: side effects outside the engine are at-least-once unless
  a tool proves an idempotency key; a write whose outcome is unknown after a
  crash is never repeated, a person is asked; BPMN is supported as a documented
  import subset; public-cloud certification and an independent security review
  have not been done.

## 7. Who to ask

Open an issue on the public repository for defects and questions. For a
design-partner pilot, use the form on the website.
