# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the ClickUp tags used for this repo, plus how to interpret them when updating task status.

| Label in mattpocock/skills | ClickUp tag | Status mapping |
| -------------------------- | ----------- | -------------- |
| `needs-triage`             | `needs-triage` | Move to the list's intake or triage status when available |
| `needs-info`               | `needs-info` | Move to the list's waiting, blocked, or needs-info status when available |
| `ready-for-agent`          | `ready-for-agent` | Move to the list's ready or backlog status when available |
| `ready-for-human`          | `ready-for-human` | Move to the list's ready or human-owned implementation status when available |
| `wontfix`                  | `wontfix` | Move to the list's closed, rejected, or won't-fix status when available |

When a skill mentions a role, apply the corresponding ClickUp tag. If the task status also needs to change, use the closest existing workflow status in the `Zebra SDK Flutter Plugin` list rather than creating a new status.
