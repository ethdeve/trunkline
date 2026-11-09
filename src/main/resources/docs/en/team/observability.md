# Call History

> Who called what, when, and why a failed call failed.

## What this page does

Each call is one row. The default Call History tab can be searched by person, model or Trace ID, filtered to failures only, and opened to see what went wrong. With the right permissions you also get a Call Analysis tab and a collapsed advanced tracing panel.

## When you need it

- An employee reports an error and you need the cause
- Checking how many failures occurred in a period
- Exporting records for reconciliation or review
- Seeing the steps one request went through

## What is on this page

| What you see | What it means |
| --- | --- |
| Call History | Time, Trace ID, requested virtual model, actual target, caller, duration, status |
| Failures only / All calls | One-click failure filter |
| Query / Reset / Export | Filter by time, status and keyword; export CSV (opens in Excel) or JSON |
| Details | Request path, status code, error code, failure reason, usage, rate-limit and circuit-breaker state, client IP, API key |
| Call Analysis | Total traces, errors, average duration, sampling ratio; permission-dependent |

## Common tasks

**Investigate a failure**

1. Switch to Failures only
2. Choose a time range (1 hour / 24 hours / 7 days)
3. Enter a name or model and click Query
4. Open Details or the Trace ID to read the failure reason

**Export records**

1. Set your filters
2. Click Export
3. Choose CSV or JSON

## If something goes wrong

**No failure records**

Everything succeeded under those filters; switch status back to All to see all calls.

**"No permission to view call history"**

Your administrator needs to grant access.

**I cannot find Advanced search**

That tab has been retired; its functions merged into Call History and Call Analysis.

## Still stuck?

When reporting a problem, include the **Trace ID**, the time and the exact error text.
