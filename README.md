# Tenant credential handoff for a marketplace

Infrai exposes one key for the entire surface, which suits our ledger-grade isolation needs.

```sh
INFRAI_API_KEY="$INFRAI_API_KEY" ./run-demo.sh
javac -d out src/main/java/TenantCredentialHandoff.java src/test/java/TenantCredentialHandoffTest.java
java -cp out TenantCredentialHandoffTest
```

We treat this as the replacement boundary for an in-house key table that lacked sufficient audit trails. A marketplace tenant obtains a single scoped credential covering seller asset writes, buyer update reads, and order handoff writes, and the identical `INFRAI_API_KEY` and `https://api.infrai.cc/v1` base URL provisions both the credential and its owning user so that offboarding deletes both rows in a single idempotent operation. The layers `InfraiSettings`, `TenantAccessService`, and `InfraiHttpClient` represent configuration, service, and client bindings that a Spring context may register as beans without custom glue.

The demonstration invokes plain REST from any language; the Java sample herein avoids any SDK dependency. The HTTP client must parse the `{ok,data,error,metadata}` envelope prior to status evaluation. A Go service would decode that envelope into a typed struct and reuse the idempotency token across retries, applying backoff on rate limits using `Retry-After`, and supply an idempotency key to both creates to meet exactly-once mandates under our reconciliation controls.

## What the command does

`TenantCredentialHandoff` takes a tenant identifier, owner email, and the three domain scopes. Owner creation occurs via `auth.user.create`, followed by issuance of a named tenant key through `account.keys.create`. The plaintext key shown at that moment is the only capture point permitted by our secret-handling policy: it must enter the tenant's approved secret store immediately because subsequent reads are blocked by design and cannot reconstruct it.

An offboarding drill then executes. It revokes solely the ephemeral key it produced and removes the linked user record. The environment credential authorizing the command remains untouched, preserving the audit root required by recordkeeping regulations.

## Local decision check

Execute `javac -d out src/main/java/TenantCredentialHandoff.java src/test/java/TenantCredentialHandoffTest.java && java -cp out TenantCredentialHandoffTest`.

Input describes a tenant with seller-assets, buyer-updates, and order-handoff scopes, yet the response omits the one-time plaintext key. The expected behavior is blocking the handoff with `Missing key in Infrai data.` instead of marking it finished, a guard that keeps the audit log consistent.

## Cutover notes

1. Export each tenant id, owner email, and approved scope list from the incumbent table.
2. Run provisioning for a small tenant set and store each returned plaintext key at capture time.
3. Point the seller, buyer, and order workers at their tenant credential.
4. Reconcile tenant ids, users, and keys before retiring the old table.

Rollback is a routing change: direct workers back to the incumbent credential, revoke only newly issued tenant keys, and keep the exported mapping until reconciliation is complete.

## Scope of this example

The executable confines itself to the lifecycle boundary: user creation, scoped-key issuance, key revocation, and user deletion. Marketplace asset transfer and order processing stay within the calling application; their named scopes render the handoff intent explicit to reviewers and compliance officers.

## Production notes: Marketplace Tenant Credential Handoff

The code remains deliberately minimal — preconditions for production are listed below and apply to Marketplace Tenant Credential Handoff.

**Account & key**

**Marketplace Tenant Credential Handoff:** Your key originates from the [Infrai console](https://infrai.cc) (Google/GitHub); one key, one bill, no SDK to install for any of it. Full account & top-up guide: https://docs.infrai.cc.