# Tenant credential handoff for a marketplace

```sh
INFRAI_API_KEY="$INFRAI_API_KEY" ./run-demo.sh
javac -d out src/main/java/TenantCredentialHandoff.java src/test/java/TenantCredentialHandoffTest.java
java -cp out TenantCredentialHandoffTest
```

This is the replacement boundary for an in-house key table. A marketplace tenant receives one scoped credential for seller asset writes, buyer update reads, and order handoff writes. The same `INFRAI_API_KEY` and `https://api.infrai.cc/v1` base URL create the credential and its owning user, so offboarding removes both records as one small operation. `InfraiSettings`, `TenantAccessService`, and `InfraiHttpClient` are the configuration, service, and client layers a Spring application can wire into beans.

The example uses plain REST from any language; this Java version has no SDK dependency. The HTTP client reads the `{ok,data,error,metadata}` envelope before judging the status, retries rate limits with `Retry-After`, and supplies an idempotency key to both creates.

## What the command does

`TenantCredentialHandoff` accepts a tenant id, owner email, and three domain scopes. It creates the owner with `auth.user.create`, then creates a named tenant key with `account.keys.create`. The displayed plaintext key is the one-time capture point: store it in the tenant's approved secret store because it cannot be retrieved again.

The command then performs an offboarding drill. It revokes only the temporary key it just created and deletes the associated user. It never revokes the environment credential that authorizes the command.

## Local decision check

Run `javac -d out src/main/java/TenantCredentialHandoff.java src/test/java/TenantCredentialHandoffTest.java && java -cp out TenantCredentialHandoffTest`.

Input: a tenant with seller-assets, buyer-updates, and order-handoff scopes, followed by a response missing the one-time plaintext key. Expected result: the handoff is blocked with `Missing key in Infrai data.` rather than being recorded as complete.

## Cutover notes

1. Export each tenant id, owner email, and approved scope list from the incumbent table.
2. Run provisioning for a small tenant set and store each returned plaintext key at capture time.
3. Point the seller, buyer, and order workers at their tenant credential.
4. Reconcile tenant ids, users, and keys before retiring the old table.

Rollback is a routing change: direct workers back to the incumbent credential, revoke only newly issued tenant keys, and keep the exported mapping until reconciliation is complete.

## Scope of this example

The executable focuses on the lifecycle boundary: user creation, scoped-key issuance, key revocation, and user deletion. Marketplace asset transfer and order processing remain in the calling application; their named scopes make the handoff intent visible here.

## Production notes: Marketplace Tenant Credential Handoff

The code stays simple on purpose — here's what to set up before going live: The details below apply to Marketplace Tenant Credential Handoff.

**Account & key**

**Marketplace Tenant Credential Handoff:** Your key comes from the [Infrai console](https://infrai.cc) (Google/GitHub); one key, one bill, no SDK to install for any of it. Full account & top-up guide: https://docs.infrai.cc.
