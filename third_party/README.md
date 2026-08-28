# Third-Party Registry

This directory records third-party material that cannot be described solely by
an ecosystem lockfile. It covers fork bases, copied or derived source,
vendored files, binaries, APKs, generated artifacts, models and datasets.

The governing policy is
[`docs/security/supply-chain-and-sbom.md`](../docs/security/supply-chain-and-sbom.md).

## Files

- `inventory.yaml` is the machine-readable registry of manually reviewed
  non-package components and known inherited gaps.
- `inventory.schema.json` defines the required registry structure for CI.
- `notices/` preserves license texts, exact upstream pins and append-only
  import/patch ledgers.

Package-manager dependencies remain authoritative in their lockfiles and are
represented in generated release SBOMs. They are not copied into
`inventory.yaml` by hand.

## Required entry fields

Every shipped entry records:

- stable id and artifact type;
- relationship to this repository;
- exact source revision or content digest;
- local paths and distribution scope;
- SPDX license expression and notice path;
- modification state;
- review status, owner and date.

References that are not distributed use `reference_only: true`. This preserves
engineering provenance without incorrectly declaring the reference as a
shipped component.

## Change rule

Do not overwrite historical import ledger rows. Add a new entry or revision,
update the relevant notice, regenerate affected release SBOMs and obtain review
in the same Pull Request.
