# Model capability profiles and one-click virtual models

> Added in v0.2.0. See what each model is good at, then build a virtual model pool and routing rules from a chosen purpose. Operators and admins only.

## What these two pages do

Two entries were added to the **Team** group:

- **Model Capabilities** — a static profile browser. Filter by use case, vendor, service type, preferred strength and maximum latency to answer "which model should I pick".
- **Scenario Templates** — a one-click wizard. Pick a purpose, preview the pools and rules that will be created, then apply them. It answers "how do I configure the model I picked".

Both live in the OSS trunk. The enterprise build inherits them from the jar; no separate enterprise implementation is needed.

## Using Model Capabilities

1. Open **Team → Model Capabilities**.
2. Use the filters at the top (use case such as "customer support", vendor, service type, preferred strength, maximum latency) and click **Apply**.
3. In the table, **Model / canonical ID** shows the connected name and its normalized ID. **Strengths** is a high/mid/low rating for code, Chinese, math and reasoning. **Source** tells you where the profile came from.

About sources: the shipped data is the built-in baseline (`builtin`) covering broadly known public capabilities. Unknown dimensions are shown as "unknown", never as "unsupported". The DTO also reserves `persisted` / `resolver` / `probe` / `spi` markers for on-site maintenance, name resolution, active probing and enterprise injection; in this release only the built-in baseline is actually active. The UI always shows the real source so a guess is never mistaken for a vendor promise.

Prices are intentionally absent from profiles: they change too often, and the project already tracks realized prices with snapshots.

## Using the one-click wizard

1. Open **Team → Scenario Templates** and pick a purpose on the left. You can also reach it from the help (question-mark) icon's access guide.
2. Click **Preview changes** to see the pools and rules that will be created. The pool name is the `model` value you will use later; rule targets are already replaced with real pool names, not template examples.
3. When satisfied, click **Apply scenario**. Pools and rules are persisted and hot-reloaded; no restart is needed.
4. Send a real request using the `model` value shown on the result page to confirm routing. You can also inspect the new entries under **Virtual Models** and **Routing Rules**.

The four built-in scenarios cover different shapes:

| Scenario | What it creates | What it verifies |
| --- | --- | --- |
| Customer support | one chat pool + a pool rate-limit rule | profile-based selection with a real pool name injected into a rule |
| Document retrieval RAG | chat / embedding / rerank pools + three SERVICE_TYPE routing rules | multiple pools and observable selection differences |
| Code assistant | one chat pool + a model-rewrite rule | subjective strength (`code`) participating in selection |
| Cost guardrail | no pool, one global rate-limit rule | the "rules only" path |

## Notes

- **Profile misses fall back**: if no profile matches, the first available instance of that service type is used and the result says "no profile match". Profiles are never invented.
- **No instance, no pool**: if a service type has no connected instances, its pool and dependent rules are skipped so no unusable rule is written.
- **Re-applying skips existing pools**: a name conflict is reported under "Notes / skipped items" and the remaining items still proceed; a partial failure never surfaces as a 5xx.
- **Rate limits are examples**: the capacity/rate values in the cost guardrail and customer-support templates are starting points; adjust them before relying on them.
- **Tenant isolation**: pools and rules follow the existing tenant-aware persistence path. New configurations belong to the current tenant; profile baselines are global read-only data.
