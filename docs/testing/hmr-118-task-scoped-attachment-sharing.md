# HMR-118 task-scoped attachment sharing gate

HMR-118 lets the user choose one file or photo from Android's system picker
before starting a task. The picker returns a temporary `content://` grant; the
launcher keeps only minimized metadata and consumes the selection when the
next task is accepted. Hermes never receives a filesystem path or URI.

The task-scoped `phone.share_attachment` capability accepts only the reviewed
exact WeChat or QQ package name. Its Android provider builds a closed `ACTION_SEND`
Intent containing the picker-selected URI, MIME type and read grant. No model
parameter can replace the URI, MIME type, filename, Intent action, flags or
extras. The capability is one-shot and disappears with the task bridge.

A successful execution means only that Android accepted the launch of the
target package's share surface. It does not mean a contact was selected or
that delivery succeeded. Hermes must continue through the existing semantic
screen observation and state-bound navigation tools, then verify the visible
postcondition. Payment and account-security screens remain subject to the
existing on-device L4/L5 denial.

Security boundaries:

- only `content://` selections are accepted;
- no broad storage or media permission is requested;
- no persistable URI permission is taken;
- known selections larger than 128 MiB are rejected;
- display names are bounded and stripped of path/control semantics;
- invalid MIME metadata falls back to `application/octet-stream`;
- the result withholds URI and attachment metadata;
- the initial target allowlist is `com.tencent.mm` and `com.tencent.mobileqq`;
- a task can launch the selected attachment share flow at most once;
- an unavailable Accessibility service or target package fails closed.

Acceptance requires shared Python/Kotlin protocol fixtures, provider and
selection-policy unit tests, the existing manifest permission allowlist, API
30/API 36 build/lint gates, and a physical iQOO Z10x run which selects both a
photo and a downloaded file and opens WeChat's share flow without granting
storage-wide access.
