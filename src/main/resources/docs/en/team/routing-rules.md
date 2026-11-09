# Routing Rules

> If a request matches a condition, send it to a chosen model.

## What this page does

Here you can write "if ... then ..." rules, for example sending one department to a cheaper model. Rules are checked top to bottom and **the first match wins**. With no rules, the default assignment applies; most companies do not need this page.

## When you need it

- Sending different departments or purposes to different models
- Sending a percentage of traffic to a new model to try it out
- Temporarily pointing a class of requests at a specific model

## What is on this page

| What you see | What it means |
| --- | --- |
| Priority | Higher numbers are matched first |
| Name / Condition / Action | Conditions include category, model name, header, source IP or percentage; actions include rewriting the model, pinning an instance, changing the adapter, splitting traffic, throttling or tag routing |
| Hits this month | How often the rule was applied |
| From template / Simulate / Add rule | Simulation shows the result without saving |

## Common tasks

**Add a rule**

1. Click Add rule
2. Enter a name and add a condition
3. Choose an action and save
4. Use Simulate with a sample request to see which rule matches

**Verify a match**

1. Click Simulate
2. Enter a sample request
3. Read the result; no need to save first

## If something goes wrong

**A rule does not apply**

A higher-priority rule probably matched first. Use Simulate to see the actual match.

**I cannot find a Response Cache tab**

That feature is not available in this release; only assignment rules exist.

## Still stuck?

Send the rule name, conditions, expected result and the simulation output to your administrator.
