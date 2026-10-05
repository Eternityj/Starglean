-- A post-publication item starts with automatically_published_at set and no
-- human review timestamp. If staff later takes it down and restores it, the
-- restored approval is a real human decision and must retain both timestamps.
alter table nearby_private.publications
  drop constraint publications_review_state_check;
alter table nearby_private.publications
  add constraint publications_review_state_check check (
    (state='pending' and reviewed_at is null and automatically_published_at is null) or
    (state='approved' and (
      (publication_mode='pre_review' and reviewed_at is not null) or
      (publication_mode='post_publication' and automatically_published_at is not null)
    )) or
    (state in ('rejected','withdrawn') and reviewed_at is not null)
  );

-- Index the referencing sides used by Auth/entry/media cascades. These tables
-- are currently small, so ordinary transactional index creation is preferable
-- to CONCURRENTLY (which cannot run inside apply_migration's transaction).
create index deletion_operations_actor_idx
  on nearby_private.deletion_operations(actor_id);
create index deletion_operations_entry_idx
  on nearby_private.deletion_operations(entry_id)
  where entry_id is not null;
create index entry_media_owner_idx
  on nearby_private.entry_media(owner_id);
create index media_access_tickets_media_idx
  on nearby_private.media_access_tickets(media_id);

comment on constraint publications_review_state_check on nearby_private.publications is
  'Automatic publication has automatically_published_at; a non-null reviewed_at additionally records a later human restore.';
