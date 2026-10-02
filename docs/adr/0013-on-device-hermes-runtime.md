# ADR 0013: Hermes executes inside the Android installation

Status: accepted product requirement; runtime implementation under evaluation.
Date: 2026-09-13

The user explicitly corrected the original host/phone architecture. The delivery
target is one installable APK containing the real Hermes Agent runtime. A PC,
remote Agent server, Root, separate Termux installation, and user-run bootstrap
commands are not acceptable prerequisites. Remote model inference is permitted.
This supersedes host-location assumptions in earlier mobile ADRs, not their
authorization, audit, state freshness, or artifact protections.

## Acceptance target

- iQOO Z10x V2445A, Android 15 / OriginOS 5, ARM64, 8 GB physical RAM.
- Setup consists of model endpoint/key selection and necessary Android grants.
- Preserve real Hermes tools, skills and memory; track unsupported features
  explicitly rather than silently substituting a basic chatbot.
- WeChat contact/message/image/file tasks execute on explicit user instruction.
- User completes interactive login challenges. Research uses logged-in apps;
  results appear in chat before any user-directed save.
- WeChat, QQ and system notification summaries are supported.
- Payment and security-setting mutations require user confirmation. Retrieved
  content and notification text cannot authorize actions.
- Background-capable work continues while locked, subject to measured Android
  lifecycle behavior. UI tasks pause for unlock; no lockscreen bypass.

## Runtime experiment

Evaluate bundled CPython through Chaquopy 17.0.0 with Python 3.11, ARM64 and
x86_64. Existing AGP 9.3.2 exceeds Chaquopy's documented tested range (through
9.2); retain the existing toolchain initially and measure compatibility.
The isolated `experiments/android-python-probe` project is a developer probe,
not the production application or an announcement of full Hermes support.

Evidence stages are distinct: APK builds; CPython executes on Android; pinned
native dependencies import; actual `run_agent` imports; real Agent completes a
model turn; phone tools and required workflows pass on the user's device.
Success in one stage does not imply later stages. Do not downgrade security
dependency floors to fit available wheels. Generate dependency metadata from
builds and review it before promoting the experiment into the production app.

A same-UID Android process is not a security boundary against arbitrary Python
code. The production phone router remains deny-by-default until a concrete local
authorization design preserves user-command provenance and confirmation gates.

Sources:
- https://chaquo.com/chaquopy/doc/current/android.html
- https://chaquo.com/chaquopy/doc/current/faq.html
