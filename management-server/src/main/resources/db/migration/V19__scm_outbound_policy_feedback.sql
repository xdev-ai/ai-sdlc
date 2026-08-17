-- P3 outbound policy feedback (scm.outbound.v1).
--
-- Until now the only outbound mechanism was a GitHub Check Run, whose identifier is a bigint. GitLab returns a
-- commit-status id, Bitbucket keys a build status by string, Azure DevOps returns a pull-request status id, and Jira
-- returns a comment id. A bigint column cannot hold all of those, so the provider-side reference becomes text and
-- policy_check_run_id is retained only so existing GitHub rows keep their original value.
alter table scm_events add column policy_feedback_ref varchar(200);
alter table scm_events add column policy_feedback_state varchar(20);
alter table scm_events add column policy_feedback_at timestamptz;

-- The connector contract carries an externalKey — a Jira issue key, for instance — and ingestConnectorDelivery had
-- nowhere to put it, so it was written into release_tag. A Jira issue key stored in a column named release_tag is
-- wrong in the ledger and unusable for addressing the issue on the way back out. Give it its own column and move the
-- existing values across; release_tag returns to meaning a release tag.
alter table scm_events add column external_key varchar(300);

update scm_events set external_key = release_tag, release_tag = null
 where provider = 'JIRA' and release_tag is not null;

-- Existing GitHub Check Runs are already published; record them in the neutral columns so the two views agree
-- rather than leaving those rows looking as though no feedback was ever sent.
update scm_events
   set policy_feedback_ref = policy_check_run_id::text,
       policy_feedback_state = 'PUBLISHED',
       policy_feedback_at = coalesce(processed_at, received_at)
 where policy_check_run_id is not null;

create index scm_events_policy_feedback_state_idx on scm_events (policy_feedback_state)
 where policy_feedback_state is not null;
