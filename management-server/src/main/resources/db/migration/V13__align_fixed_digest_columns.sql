-- Keep historic migrations immutable. Hibernate maps Java String digest fields as varchar(64),
-- so normalize the legacy fixed-width PostgreSQL char columns before schema validation.
alter table notification_deliveries
  alter column payload_sha256 type varchar(64),
  alter column recipient_fingerprint type varchar(64);

alter table notification_delivery_receipts
  alter column payload_sha256 type varchar(64);

alter table scim_service_principals
  alter column token_sha256 type varchar(64);
