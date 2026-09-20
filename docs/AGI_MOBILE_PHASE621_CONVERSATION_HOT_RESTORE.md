# Phase621 — Conversation Hot Restore Across APK Updates

Phase621 fixes a UI continuity gap observed after installing an AMPER update over the existing app.

## Observed behavior

Conversation turns remained persisted in sovereign memory, but the new Android process initialized:

- the active conversation from a generic/default fallback;
- the visible inference transcript as empty.

The user therefore had to open the persisted conversation manually before its content reappeared.

## Hot restore policy

At startup AMPER now resolves the active conversation in this order:

1. pending side-effect approval checkpoint, when one must be reviewed safely;
2. the last conversation explicitly open in the UI;
3. the latest restored persistent plan conversation;
4. the most recently persisted conversation thread;
5. the primary conversation.

The selected conversation id is stored in app-private SharedPreferences whenever it changes. Android APK
updates preserve this app data when the package is installed over the existing app.

## Automatic transcript hydration

Immediately after choosing the startup conversation, AMPER loads its recent persisted turns and
hydrates the visible conversation output without requiring the user to press View transcript.

No inference, tool execution, or action approval is triggered by this hydration. It is read-only.

## Safety

A pending assistant side-effect approval remains higher priority than ordinary UI continuity.
Restoring transcript text never executes the pending action.

## Single-core UI consistency

The local smoke-test status now describes the current architecture:

`AMPER Core admission -> AMI/AMNE generation -> single-core local inference`

instead of the removed staged-GGUF/foreign-runtime wording.
