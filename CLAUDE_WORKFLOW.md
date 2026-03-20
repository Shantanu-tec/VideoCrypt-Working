# Claude Workflow — Session Instructions

One file, two sections. Copy the relevant section at the right moment.
**Session start** → copy the 🟢 SESSION START block and paste to Claude.
**Session end** → copy the 🔴 SESSION END block and paste to Claude.

---

## 🟢 SESSION START

Copy everything between the dashes and paste to Claude.

---

I'm continuing work on the EducryptMedia SDK project.

Before doing ANYTHING, read these files in order:

1. `CLAUDE.md`      — SDK architecture, public API surface, file index, rules
2. `TASKS.md`       — Current goal, last session snapshot, what's next
3. `SCRATCHPAD.md`  — Open questions, SDK patterns, AAR release checklist

After reading all three:
- State the current session goal (from TASKS.md "Current Goal")
- If "Current Goal" is empty, ask me what to work on this session
- Confirm whether the task touches the public API — flag immediately if yes
- Confirm the deliverable before starting any work

### During Work
- Discover a gotcha?                → Add to CLAUDE.md "Gotchas" immediately
- Making an architectural decision? → Log in TASKS.md "Decisions Made"
- Uncertainty about public API?     → Stop and ask before proceeding
- Moving between tasks?             → Update TASKS.md sections

### Critical Rules

SDK Public API (HIGHEST PRIORITY):
- NEVER remove or rename a public method — deprecate first, keep old working
- NEVER change a public model's field names/types — it breaks client deserialization
- ALWAYS ask "is this a breaking change?" before modifying anything in playback/, downloads/interfaces, models/
- ANY new public class → add keep rule to consumer-rules.pro

Realm (HIGH PRIORITY):
- NEVER add/remove/rename DownloadMeta fields without bumping schemaVersion in RealmManager
- NEVER pass Realm objects across threads — query fresh in each context
- Realm schema v1 — any field change needs schemaVersion(2) + migration block

Code Quality:
- NEVER use unsafe !! operator in SDK code — crashes affect client apps
- NEVER hardcode URLs, keys, or tokens in SDK source — accept via client params

Out of Scope (NEVER touch without explicit confirmation):
- AES.kt
- EncryptionData.kt
- realm/entity/DownloadMeta.kt
- interfaces/Apis.kt
- consumer-rules.pro (touch ONLY when adding new public class)
- EducryptMediaSdk/build.gradle.kts

### Build Commands
```
./gradlew :EducryptMediaSdk:assembleRelease   # Build release AAR
./gradlew :EducryptMediaSdk:assembleDebug     # Build debug AAR
./gradlew :app:assembleDebug                  # Test SDK via demo app
./gradlew :EducryptMediaSdk:test              # SDK unit tests
./gradlew clean                               # Clean all
```

### AAR Output
`EducryptMediaSdk/build/outputs/aar/EducryptMediaSdk-release.aar`

### Current Session Deliverable
Check TASKS.md "Current Goal"

---

## 🔴 SESSION END

Copy everything between the dashes and paste to Claude.

---

Work for this session is complete. Update all project documentation now.

### TASKS.md (Always update)

"Last Session Snapshot" — overwrite with 4–5 fresh lines:
- What was done this session
- Whether any public API was changed (and what)
- Where work ended exactly
- What to do next session
- Any warnings for next session

"Done": Move all "In Progress" → "Done" with today's date + ✅ + files modified + build status
"Up Next": Add newly discovered tasks; keep 🔴 SDK tasks above demo app tasks
"In Progress": Clear completely
"Current Goal": Clear completely
"Decisions Made": Add any decisions — format: ### YYYY-MM-DD: [Title] + Decision/Why/Impact

### CLAUDE.md (Update if applicable)
- "Public API Surface": Update if any public methods/models were added or deprecated
- "Gotchas": Add new ones (❌ WRONG vs ✅ RIGHT)
- "Quick File Finder": Add paths for any new files created
- "Out of Scope": Add newly discovered fragile files

### SCRATCHPAD.md (Update if applicable)
- "Open Questions": Add new, mark resolved with [x]
- "Technical Debt": Add new rows to the table
- "AAR Release Checklist": Update if new release steps discovered

### Checklist
- [ ] TASKS.md "Last Session Snapshot" updated
- [ ] TASKS.md "Done" updated with today's work
- [ ] TASKS.md "In Progress" cleared
- [ ] TASKS.md "Current Goal" cleared
- [ ] TASKS.md "Decisions Made" updated (if applicable)
- [ ] CLAUDE.md "Public API Surface" updated (if API changed)
- [ ] CLAUDE.md "Gotchas" updated (if new issues found)
- [ ] consumer-rules.pro updated (if new public class added)
- [ ] SCRATCHPAD.md updated (if needed)
- [ ] SDK build verified: `./gradlew :EducryptMediaSdk:assembleRelease`
- [ ] Demo app verified: `./gradlew :app:assembleDebug`

### Response Format

✅ Session Complete

Completed:
- [bullet list]

Public API Changes: [None / list what changed and whether it's breaking]

Files Modified/Created:
- SDK: [files]
- Demo app: [files]

Build Status:
- SDK AAR: ✅ BUILD SUCCESSFUL / ❌ FAILED
- Demo app: ✅ BUILD SUCCESSFUL / ❌ FAILED

consumer-rules.pro: [Not changed / Updated — added keep rule for X]

Documentation Updated:
- [which files, what was added]

Next Steps (from TASKS.md "Up Next"):
1. [task]
2. [task]
