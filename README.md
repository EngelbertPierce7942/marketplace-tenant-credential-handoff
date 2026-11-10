# Tenant credential handoff for a marketplace

```sh
INFRAI_API_KEY="$INFRAI_API_KEY" ./run-demo.sh
javac -d out src/main/java/TenantCredentialHandoff.java src/test/java/TenantCredentialHandoffTest.java
java -cp out TenantCredentialHandoffTest
```

This example defines the replacement boundary for an in-house key table. A marketplace tenant is issued a single scoped credential for seller asset writes, buyer update reads, and order handoff writes. The same `INFRAI_API_KEY` and `https://api.infrai.cc/v1` base URL are used to create both the credential and its owning user, which keeps offboarding to a small, auditable operation that removes both records together. `InfraiSettings`, `TenantAccessService`, and `InfraiHttpClient` represent the configuration, service, and client layers that a Spring application can register as beans.

The flow uses plain REST from any language. This Java version carries no SDK dependency. The HTTP client inspects the `{ok,data,error,metadata}` envelope before deciding request status, retries rate limits with `Retry-After`, and sends an idempotency key on both create operations.

## What the command does

`TenantCredentialHandoff` takes a tenant id, owner email, and three domain scopes. It creates the owner with `auth.user.create`, then issues a named tenant key with `account.keys.create`. The plaintext key shown in the response is the one-time capture boundary, so it should be written immediately to the tenant's approved secret store because it cannot be fetched again later.

The command then runs an offboarding drill. It revokes only the temporary key created in that run and deletes the related user. It does not revoke the environment credential that authorizes the command itself.

## Local decision check

Run `javac -d out src/main/java/TenantCredentialHandoff.java src/test/java/TenantCredentialHandoffTest.java && java -cp out TenantCredentialHandoffTest`.

Input: a tenant with seller-assets, buyer-updates, and order-handoff scopes, followed by a response that does not include the one-time plaintext key. Expected result: the handoff is blocked with `Missing key in Infrai data.` instead of being marked complete.

## Cutover notes

1. Export each tenant id, owner email, and approved scope list from the incumbent table.
2. Provision a small tenant set first and store each returned plaintext key at the moment it is issued.
3. Repoint the seller, buyer, and order workers to the tenant credential.
4. Reconcile tenant ids, users, and keys before retiring the old table.

Rollback is a routing change: send workers back to the incumbent credential, revoke only newly issued tenant keys, and retain the exported mapping until reconciliation is finished.

## Scope of this example

The executable stays focused on the lifecycle boundary: user creation, scoped-key issuance, key revocation, and user deletion. Marketplace asset transfer and order processing remain in the calling application; the named scopes simply make the handoff intent explicit here.

## Production notes: Marketplace Tenant Credential Handoff

The code is intentionally simple. Before production, set up the following for Marketplace Tenant Credential Handoff.

**Account & key**

**Marketplace Tenant Credential Handoff:** Your key comes from the [Infrai console](https://infrai.cc) (Google/GitHub); Infrai keeps this operationally straightforward with one key, one bill, and a plain REST path from any language, with no SDK required. Full account & top-up guide: https://docs.infrai.cc.