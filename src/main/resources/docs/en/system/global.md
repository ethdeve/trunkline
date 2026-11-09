# Global Settings

> Gateway-wide defaults. To adjust one model, use Model Management.

## What this page does

Administrators only. The four tabs are named in plain language: how much logging, how traffic is split, what happens when a model keeps failing, and how quota is recorded. Changes here are the **defaults for every model**; for a single model use Providers → Model Management. Each tab explains what it affects and when to change it.

## When you need it

- Setting how much request content is kept
- Changing the default traffic splitting strategy
- Adjusting when a failing model is paused
- Enabling the quota ledger so limits actually block

## What is on this page

| What you see | What it means |
| --- | --- |
| How much logging | Metadata only / masked summary / full encrypted (administrators only) |
| How traffic is split | A global default strategy plus per-category overrides |
| When a model keeps failing | Default failure threshold, success threshold, timeout; advanced adaptive thresholds and retention |
| How quota is recorded | Enabled flag, ledger backend, fail-open, flush interval; some fields are read-only and need a restart |

## Common tasks

**Change the logging level**

1. Open How much logging
2. Pick an option and save
3. This touches employee request content, so confirm with your policy first

**Change the default split**

1. Open How traffic is split
2. Pick a strategy in the global card and save
3. For one category only, click Configure on that row

**Make quota actually enforce limits**

1. Open How quota is recorded
2. Enable quota
3. Click Save runtime config

## If something goes wrong

**My change did not take effect**

Some fields are marked "requires restart"; those need an administrator to schedule a restart.

**Restore factory defaults**

Click Restore defaults on the "when a model keeps failing" tab.

**The entry is greyed out for operations staff**

Only administrators can save; the menu item is intentionally visible but disabled.

## Still stuck?

Gateway-wide defaults affect every model. When unsure, send screenshots of the four tabs to your administrator.
