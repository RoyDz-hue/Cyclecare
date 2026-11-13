-- DRAFT additive schema only. Never run against production. Existing schema version9 unchanged.
CREATE TABLE IF NOT EXISTS local_commit_receipts (
 ownerNamespace TEXT NOT NULL,
 operationId TEXT NOT NULL,
 intentDigest TEXT NOT NULL,
 resultVersion INTEGER NOT NULL,
 resultJson TEXT NOT NULL,
 committedAt INTEGER NOT NULL,
 PRIMARY KEY(ownerNamespace, operationId)
);
-- No outbox FK: a delivered/deleted outbox must not erase local commit evidence.
-- No destructive migration, no automatic backfill/receipt claims for legacy writes.
