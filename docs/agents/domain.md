# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Layout

This is a single-context repo. It is a Flutter plugin wrapper around the Zebra SDK.

## Before exploring, read these

- `CONTEXT.md` at the repo root.
- `docs/adr/` for architectural decisions that touch the area being changed.

If these files do not exist, proceed silently. Do not flag their absence or suggest creating them upfront. The producer skill (`/grill-with-docs`) creates them lazily when terms or decisions actually get resolved.

## Use the glossary's vocabulary

When output names a domain concept in an issue title, refactor proposal, hypothesis, or test name, use the term as defined in `CONTEXT.md`.

If the concept is not in the glossary yet, either reconsider whether the term belongs in this project or note the gap for `/grill-with-docs`.

## Flag ADR conflicts

If output contradicts an existing ADR, surface it explicitly rather than silently overriding it.
