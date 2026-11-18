# Automate, SkillForge and Plugins (v1.6)

## Where things are
- **Automate tab** (next to Models): AutoPilot | SkillForge | Plugins.
- **+ button in chat > Plugin**: opens the same Plugin manager.
- Code: `app/src/main/java/com/astra/agent/hub/` (engine) and `ui/AutomateScreen.kt`, `SkillForgeScreen.kt`, `PluginScreen.kt`, `HubKit.kt`.

## AutoPilot
Plan -> mini-agents (one per step) -> dependency-ordered run (independent steps in parallel) -> recovery (wait/retry, regenerate with shorter input, max 2 retries) -> verification -> JSON run report.
Test run executes reads but skips anything that changes data or creates files. "Save + daily" registers a daily task through the existing scheduler: a notification appears and tapping it runs the request.

## Skills (62)
60 priority skills plus Code Analysis and Document Verification helpers. `SkillEngine` selects skills from the request (keyword scoring), adds their dependencies, and loads only those instructions into the prompt. Custom skills can be created from plain text or imported (instructions that try to override rules or leak keys are removed).

## Plugins
Real connectors: Google Workspace, GitHub, Microsoft 365, Notion, Slack, Trello, Jira, Dropbox, Discord, Telegram, plus Custom REST plugins. Phase 3 services are listed as **Planned** and cannot be connected.
- Token plugins: paste a token; it is verified against the service before it is kept.
- Google / Microsoft: OAuth 2.0 + PKCE in the browser with a loopback redirect. You create your own OAuth client (see the setup text on each card); no secrets ship in the app.
- Tokens are encrypted with an Android Keystore AES-GCM key.
- Sending / deleting asks first (Plugins > Settings changes this).
- Every plugin result starts with OK / ERROR / CANCELLED; writes are read back where the API allows.
