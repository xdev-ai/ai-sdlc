alter table inference_budget_decisions
  add column usage_event_id uuid references inference_usage_events(id) on delete restrict;

create unique index inference_budget_decisions_usage_event_unique
  on inference_budget_decisions(usage_event_id)
  where usage_event_id is not null;
