# All Keys

> Every key issued across the company, in one place.

## What this page does

Operations staff and administrators create keys here, choose which virtual models each key may use, set monthly quotas, and check how a key has been behaving. Four cards on top show total keys, active this month, expiring soon, and issued but unused.

## When you need it

- Issuing a key to a new employee
- Debugging "it does not work" reports
- Changing someone monthly quota
- Reclaiming a key when someone leaves

## What is on this page

| What you see | What it means |
| --- | --- |
| Create key | Purpose, holder, allowed virtual models, monthly quota |
| Purpose / Holder / Allowed models / Monthly quota / Used / Expires / Status | Main columns |
| Diagnose | See recent activity and the most common causes of failure |
| Quota / Edit / Revoke / More | Row actions; revoke means disable and can be undone |
| Status filter / Search / bulk actions | Find and manage several keys at once |

## Common tasks

**Issue a key**

1. Click Create key
2. Fill purpose, holder, allowed virtual models and monthly quota
3. Save
4. Ask the holder to copy it from My Keys

**Debug "it does not work"**

1. Click Diagnose on that row
2. Check status, quota and the last 24 hours
3. Usual causes: a vendor address instead of the gateway address, an unselected virtual model, or an expired key

**Change a quota**

1. Click Quota
2. Edit the monthly quota
3. Save quota

## If something goes wrong

**"No virtual models available"**

Create one in Virtual Models first, otherwise there is nothing to select when issuing a key.

**Quota warning says limits will not block requests**

The quota ledger is disabled by default. An administrator can enable it in Global Settings → Quota.

**Revoke versus delete**

Revoke only disables the key and can be undone; delete cannot be reversed.

## Still stuck?

Include the holder account, key purpose and the exact error text when asking for help.
