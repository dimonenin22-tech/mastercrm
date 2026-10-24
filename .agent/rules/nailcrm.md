# /nailcrm Slash Command Rule

When the user types `/nailcrm` (or requests a new white-label instance of JuNailCRM), immediately activate and follow the instructions in [white-label-junailcrm](file:///d:/Vibeapp/.agent/skills/white-label-junailcrm/SKILL.md).

## Protocol
1. Check if the required client variables (`{{BOT_TOKEN}}`, `{{BOT_USERNAME}}`, `{{MASTER_TELEGRAM_ID}}`, `{{STUDIO_NAME}}`, `{{BACKEND_PUBLIC_URL}}`, `{{APP_ICON_PATH}}`, `{{HOSTING_METHOD}}`) were provided.
2. If any required variables are missing, prompt the user for them.
3. Run `python scripts/rebrand_and_deploy.py` with the provided parameters.
4. Verify all Golden Invariants (Retrofit `@JvmSuppressWildcards`, Room database version 7, unkillable background sync service, idempotent stock reconciliation, and valid Python syntax).
