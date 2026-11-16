# Current Antigravity/Gemini project context

Read `AGENTS.md`, `docs/handoff/CONTINUE_HERE.md`, and `reports/Final implementation plan.md` before making changes. The active app is **`bitchord/`**, not `src/`. Work on `codex/bitchord-base` for the current baseline task. The user approved reusing the actual BitChord UI and donor code. Do not recreate the gold workstation shell or replace the complete BitChord UI with an approximation.

For the immediate work, Antigravity owns the missing translation resources in `bitchord/app/src/main/res/values-*/strings.xml`, and screen verification of the existing BitChord app. Coordinate with Claude before touching other files. Claude owns build/lint triage, native/JNI and baseline integration. Only Claude performs commits/pushes while both agents use the same checkout. Neither agent resets, cleans, switches branches, clears app data or rewrites the other's work.

The baseline build succeeded; Android/shared tests total 1,381 cases with 17 failures and one skipped. All 17 failures are `LocaleStringsTest` completeness checks. Lint reports 269 errors, 700 warnings and 5 hints, plus Kotlin metadata compatibility diagnostics during analysis. These are measured donor baseline issues, not resolved gates. Do not disable tests/lint or translate by deleting base keys.

The continuation document contains reproducible commands, runtime evidence, unresolved items and copyable prompts for both tools. Preserve the existing engine safety constraints and provider playback boundaries.
