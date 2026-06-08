# Issue Tracker: ClickUp

Issues and PRDs for this repo live in ClickUp.

## Target

- Workspace ID: `36838848`
- Space: `Ledger`
- List: `Zebra SDK Flutter Plugin`
- List ID: `901615175288`
- URL: `https://app.clickup.com/36838848/v/li/901615175288`

## Conventions

- Create implementation issues as tasks in the `Zebra SDK Flutter Plugin` list.
- Use the triage tags documented in `docs/agents/triage-labels.md`.
- When a triage tag implies a task should move through the list workflow, set the task status to the closest matching ClickUp status available in the list.
- Keep task titles action-oriented and scoped to this Flutter plugin wrapper.
- Put acceptance criteria and relevant code paths in the task description.

## When a skill says "publish to the issue tracker"

Create a ClickUp task in list `901615175288`. If the task is part of a PRD or larger plan, include a short parent/initiative reference in the description or link it to the relevant parent task if one already exists.

## When a skill says "fetch the relevant ticket"

Read the referenced ClickUp task. If only the list is referenced, search within list `901615175288` first.

## Tooling

Prefer the ClickUp connector when it is available. For REST fallback, use `CLICKUP_API_TOKEN` with the ClickUp API and verify authentication before making changes.
