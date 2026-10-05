-- psql review mirror: apply after public_nearby_service.sql.
-- The deployed 20260926180757 migration remains the single reviewed definition
-- for the additive public-app contract. Keeping this as an include prevents the
-- review mirror from drifting from post-publication timestamps, cancellation
-- tombstones, private media, account deletion, and constellation validation.
\ir ../migrations/20260926180757_complete_public_app_contract.sql
\ir ../migrations/20260926195340_fix_post_publication_restore_and_fk_indexes.sql
\ir ../migrations/20260926195430_add_private_qa_read_allowlist.sql
\ir ../migrations/20260926200545_enforce_cloud_entry_limit.sql
\ir ../migrations/20260926200608_normalize_media_deletion_lock_order.sql
