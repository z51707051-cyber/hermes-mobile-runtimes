# HMR-117 foreground task lifecycle gate

HMR-117 keeps an explicitly started Hermes task alive when its launcher
Activity is stopped, recreated, or covered by another app. The process-wide
`HermesTaskCoordinator` owns the single task controller. A private
`dataSync` foreground service displays a persistent, content-private
notification and provides a notification action which stops the task and
revokes its Android tool bridge.

The lifecycle boundary is intentionally narrow:

- only a visible launcher interaction can start a task and its foreground
  service;
- Android 13 and newer must grant task-notification permission before the
  launcher accepts the task;
- the service is not exported, has no intent filter and exposes no Binder;
- task prompts and responses remain memory-only and never appear in the
  notification;
- a non-reference-counted partial wake lock is held only while the task is
  active and has a hard 16-minute timeout, one minute longer than the embedded
  Agent's 15-minute task budget;
- completion, failure, cancellation, service destruction, or Android 15
  foreground-service timeout releases the wake lock and removes the
  notification;
- the service is `START_NOT_STICKY`, so process death or reboot never silently
  restarts a task with stale authority.

Android 15 limits `dataSync` foreground services to six background hours in a
rolling 24-hour period. Hermes' 15-minute per-task limit is deliberately far
below that platform ceiling. Low Power Standby and OEM battery-management
policies can still suspend networking; the iQOO/OriginOS acceptance run must
therefore test screen-off execution with background power usage allowed for
the app.

Lock-screen support means model, notification-reading and non-UI work can
continue while the display is off. It does not bypass the Android lock screen,
Private Space, authentication prompts, or secure windows. UI automation which
requires an unlocked screen must pause or fail with the existing typed policy
result.

Acceptance requires:

1. pure state-policy tests keep the service only for `RUNNING` and `STOPPING`;
2. source and merged-manifest checks require the exact foreground-service,
   notification and wake-lock permissions and reject exported/widened task
   services;
3. API 30 and API 36 build, lint and emulator lanes stay green;
4. the complete Android 15 launcher route still completes a real Agent tool
   turn;
5. a physical iQOO Z10x run verifies screen-off model execution, notification
   visibility, notification-stop revocation and return-to-launcher state.

This gate does not add reboot persistence, hidden background starts or battery
optimization exemptions. File/photo sharing is a separate HMR-118 gate;
production WeChat flows still require physical-device acceptance.
