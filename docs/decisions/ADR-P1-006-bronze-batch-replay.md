# ADR-P1-006: Bronze Accumulates by Batch, and a Replay Is Opt-In

- Status: Accepted
- Date: 2026-08-09
- Decision owners: Platform architect and architecture owner
- Supersedes: the `createOrReplace` write in `IngestionJob` (task `P1-3.3-V1`)

## Context

The architecture (§6.4.6, §11.1) states that bronze is *"append-only, always —
source fidelity, replay, and audit require immutability; corrections arrive as
new rows, never as mutations."*

The first implementation of `IngestionJob` wrote bronze with `createOrReplace`,
justified at the time by re-run convergence: running the same landing file twice
had to produce the same table rather than double its rows, and bronze has no
business key to deduplicate on. That reasoning is sound for one file and wrong
for two. A second, different landing file replaced the whole table, and no test
would have noticed, because no test ingested twice.

Making bronze append raises a question the previous design never had to answer:
what should happen when the same delivery arrives again? Re-running a failed
pipeline is ordinary operational work, and appending the same rows a second time
is data corruption of the simplest kind.

## Decision

Each landing file is a **batch** with an identity supplied by the caller
(`--batchId`), carried on every row as `stratus_batch_id`, and used as bronze's
identity partition key.

- A new batch id **appends**.
- A batch id the table already holds is **refused** (`--onExistingBatch fail`,
  the default). The message names the batch and the table.
- A replay is possible but must be asked for: `--onExistingBatch replace`
  rewrites that batch and only that batch, by an explicit predicate on the batch
  id.

Refusing by default is the stronger position for evidence as well as for safety.
A re-run that silently converged would be indistinguishable from a job that
wrote nothing at all, and the second of those is the harder failure to notice.

Two implementation choices are part of this decision because reversing them
would change what the table can hold:

- **`overwrite(predicate)`, never `overwritePartitions()`.** Iceberg's dynamic
  overwrite deletes the entire table when the partition spec is absent
  (`BaseReplacePartitions` falls back to `deleteByRowFilter(alwaysTrue())`) and
  reports success. An explicit predicate either replaces the named batch or
  fails; it cannot quietly truncate.
- **Identity partitioning on the batch id.** It is what makes the replay
  predicate a metadata operation that cannot reach another batch's files. It is
  also an unbounded partition key, which is the wrong shape for a table taking
  thousands of deliveries; production bronze would partition by ingest day and
  identify batches within it. The harness value of a cheap, provably scoped
  replay is worth the trade at this scale, and the trade is recorded here rather
  than left in the code to be discovered.

## Consequences

- A replay commits an Iceberg snapshot whose operation is `overwrite`, on a zone
  the architecture describes as append-only. That is a deliberate, named
  exception, not a drift: the default path is a pure append, the exception is
  opt-in per invocation, and `stratus.append-only=true` remains on the table as a
  discoverable marker of the contract. Iceberg does not enforce it, and this ADR
  is the reason the marker can be trusted to mean something.
- The corrected form of a delivery replaces the defective one under the same
  batch id, so bronze holds one version of each delivery rather than a defective
  batch followed by a corrected one that nothing distinguishes.
- Every row gains `stratus_batch_id`, `stratus_ingested_at` and
  `stratus_source_file`. These are ingestion provenance and belong to bronze;
  the transform job drops them, because a silver row is rewritten by whichever
  batch last corrected it and carrying the original batch id forward would state
  something that stops being true.
- `--batchId` is required, so every existing submission must be changed. That is
  intended: a delivery with no identity cannot be replayed, and defaulting it to
  the run id would have made every re-run a new batch and doubled the rows.
- Proven on the live cluster by `SparkIncrementalLoadVerificationTest`, whose
  fourth scenario is the one the previous suite could not have: a second batch
  must accumulate rather than replace the first.
