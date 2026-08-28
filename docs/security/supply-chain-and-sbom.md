# Supply-Chain, License and SBOM Policy

- Status: Phase 1 baseline policy
- Owner: Hermes Mobile Runtime maintainers
- Last reviewed: 2026-08-28
- Tracking: HMR-002

## 1. Purpose

Hermes Mobile Runtime combines an existing Python agent, an Android
application, generated protocol artifacts, third-party capability code,
plugins, Skills and potentially models or datasets. A single package list
cannot describe those obligations or risks.

This policy defines what must be inventoried, which SBOMs a release must
produce, how licenses are reviewed, and which supply-chain failures block a
build. It complements the upstream Hermes supply-chain controls; the stricter
rule applies.

## 2. Release rule

A release is not eligible for distribution unless all shipped components are
traceable to source and every component has:

- an exact version, commit or content digest;
- a known origin and acquisition method;
- a machine-readable component identity where the ecosystem supports it;
- a concluded license disposition;
- required copyright and notice text;
- vulnerability and integrity scan results;
- a record of whether the component was modified;
- an owning reviewer and review timestamp.

`NOASSERTION`, `UNKNOWN`, a floating branch/tag, or a missing artifact digest is
a release blocker. A reference repository that is not copied or distributed is
still recorded for engineering provenance, but is marked `reference_only` and
excluded from the shipped-component SBOM.

## 3. Required inventories

Inventory and SBOM scope is separated by artifact type. Combining the outputs
into a release index is allowed; collapsing their license conclusions is not.

| Scope | Authoritative input | Required release output |
|---|---|---|
| Python control plane | `pyproject.toml`, `uv.lock`, selected extras | Runtime and build/development component SBOMs |
| JavaScript/TypeScript | Each distributed `package-lock.json` and workspace selection | Runtime and build SBOM per shipped application |
| Rust/Tauri | `Cargo.toml` and committed `Cargo.lock` | Crate SBOM including build dependencies |
| Android | Gradle dependency graph, version catalog/lock data, AAR/JAR/native libraries | APK/AAB SBOM plus packaged-file inventory |
| Container/Nix | Docker base digest, OS package database, `flake.lock` | Image/environment SBOM |
| Vendored/derived source | `third_party/inventory.yaml`, notices and import ledger | Source component plus modification relationship |
| Skills/plugins | Manifests, scripts, assets and provenance | Installed/bundled component inventory |
| Models/tokenizers | Model card, weights/config/tokenizer digests and license | Separate model SBOM/inventory |
| Datasets/evaluation data | Dataset revision, source, terms, data card and digest | Separate dataset inventory |
| APKs/binaries/tools | Publisher, signing identity, download source and digest | Binary inventory; never inferred from filename |
| Generated contracts | Generator version, source schema digest and output digest | Build provenance relationship |

API-only model providers are recorded as external services and data processors;
their remote models are not represented as bundled weights.

## 4. Formats and artifact layout

The canonical machine-readable release SBOM is
[CycloneDX JSON](https://cyclonedx.org/docs/latest/json/) using the current
supported 1.x schema. SPDX output is also produced when required by a customer,
platform or legal workflow; [SPDX 3.0](https://spdx.dev/use/specifications/) is
the current standard at the time of this decision.

Generated outputs are release artifacts, not hand-edited source files:

```text
release/sbom/
  index.json
  python-runtime.cdx.json
  python-build.cdx.json
  android-app.cdx.json
  android-packaged-files.json
  node-<application>.cdx.json
  rust-bootstrap.cdx.json
  container-<image>.cdx.json
  models.cdx.json
  datasets.cdx.json
  notices.txt
  provenance.intoto.jsonl
```

`index.json` binds every SBOM and notice bundle to the release Git commit,
artifact SHA-256, build identity and generation command. Release provenance
uses the [SLSA v1 provenance predicate](https://slsa.dev/spec/v1.2/) in an
in-toto statement. A signed checksum without build inputs is not a substitute
for provenance.

## 5. Repository provenance records

`third_party/inventory.yaml` is the human-reviewable source registry for
non-package-manager components. `third_party/notices/` preserves license text,
source pins and import/patch history. It is not a replacement for generated
dependency SBOMs.

When source is copied or substantially derived:

1. pin the exact upstream commit before copying;
2. record upstream and downstream paths;
3. preserve the original license and notices;
4. identify modifications in an append-only import ledger;
5. add tests owned by this repository;
6. regenerate the affected SBOM and notice bundle;
7. review later upstream changes in a dedicated PR.

No code may be copied from a search result, release archive, APK or model output
when its origin and license cannot be proven.

## 6. License disposition

License compatibility is evaluated for the actual form of distribution:
source, linked library, bundled asset, APK, container, model weights and data
may have different obligations.

This table is an engineering release gate, not a substitute for qualified
legal review when commercial distribution or non-standard terms are involved.

| Disposition | Examples | Default action |
|---|---|---|
| Permissive | MIT, BSD-2-Clause, BSD-3-Clause, Apache-2.0, ISC, Zlib | Allow after notice and attribution checks |
| Public-domain style | CC0-1.0, SQLite public-domain dedication | Verify provenance and jurisdictional notice |
| Weak copyleft | MPL-2.0, LGPL family | Legal/maintainer review; document file/linking obligations |
| Strong/network copyleft | GPL family, AGPL family | Block bundling by default; explicit legal and architecture approval required |
| Source-available/non-commercial/custom | SSPL, BSL variants, research/non-commercial model or dataset terms | Block commercial distribution until written approval |
| Unknown/conflicting | Missing license, contradictory metadata, `NOASSERTION` | Block import and release |

Invoking a separately installed program through a process boundary does not by
itself settle license obligations. The distribution, coupling, installation
flow and user-facing product must still be reviewed. License metadata from a
package registry is evidence, not a legal conclusion.

## 7. Dependency and integrity controls

- Commit every lock file used by a released artifact.
- Use immutable package versions and repository commits; avoid floating tags.
- Verify package hashes/signatures when the ecosystem supports them.
- Enable [Gradle dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html)
  before Android release builds.
- Require a reviewed diff for dependency, lockfile, installer, GitHub Action,
  download URL, build-script and generated-code changes.
- Disallow unreviewed install scripts and unexpected package lifecycle scripts.
- Pin container base images by digest for releases.
- Record every network-fetched build input in provenance.
- Scan source and built artifacts for credentials before publication.
- Generate the SBOM from the final packaged artifact as well as from dependency
  declarations; declared and shipped contents must reconcile.
- Sign release APK/AAB, containers and provenance in protected CI, never on an
  untrusted developer workstation.

For Android, an unexpected permission, exported component, native library or
cleartext-network change is a security review trigger even when dependencies
are unchanged.

## 8. Pull-request and release gates

### Dependency-changing pull request

The PR must include:

- motivation and owning component;
- old/new version or SHA and upstream changelog/security notes;
- normalized lockfile diff;
- license and notice disposition;
- vulnerability scan result and accepted-risk owner, if any;
- regenerated relevant SBOM or a deterministic CI artifact link;
- tests for the affected capability.

### Release

CI fails closed when:

- a shipped component is absent from the release SBOM;
- a required lockfile is missing or dirty;
- package integrity verification fails;
- license disposition is missing or blocked;
- required notice text is absent;
- critical/high vulnerability policy is violated without a time-bounded,
  owner-approved exception;
- provenance does not bind the release artifact to the reviewed source commit;
- generated and packaged component inventories disagree.

Exceptions are stored as versioned records containing component, advisory,
scope, compensating control, owner and expiry. Expired exceptions fail builds.

## 9. Baseline inventory and gaps

The following counts were measured at downstream commit
`261302f58ddb55eae0bcef1f123bed5acb0dab91`. They are lockfile entries, not
necessarily unique runtime components:

| Input | Observed entries | Baseline status |
|---|---:|---|
| `uv.lock` | 254 packages | Pinned; license report/SBOM not yet generated |
| Root `package-lock.json` | 1,417 package paths | Pinned workspace graph |
| `website/package-lock.json` | 1,390 package paths | Separate graph |
| WhatsApp bridge lock | 167 package paths | Separate graph |
| Photon sidecar lock | 138 package paths | Separate graph |
| `flake.lock` | 8 nodes | Pinned inputs |
| Rust manifests | 1 | No committed `Cargo.lock`; release blocker for that artifact |
| Repository license/notice files | 11 | Inherited baseline; completeness review pending |
| Downstream Android Gradle graph | 0 | Android product not imported |
| Mobile Runtime model/dataset/APK artifacts | 0 | None approved for distribution |

The inherited vendored SQLite headers, bundled Skills/plugins and separate
application lockfiles require continued upstream inventory reconciliation.
This is recorded debt, not permission to omit them from a release SBOM.

## 10. Phase 1 acceptance

Before HMR-101 imports Android source:

1. the imported file group has an approved `third_party/inventory.yaml` entry;
2. the hermes-android import ledger names every derived path;
3. Gradle dependency locking/verification and license reporting are configured;
4. the debug APK SBOM is generated in CI;
5. manifest permissions and exported components have an explicit diff gate;
6. no model, dataset, Portal APK or binary is bundled by implication.

Before the first distributable release, all gaps in §9 affecting shipped
artifacts must be closed.
