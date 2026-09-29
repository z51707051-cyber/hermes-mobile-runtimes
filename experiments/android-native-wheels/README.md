# Android native wheel experiments

Hermes core currently pins native packages which do not all publish Android
wheels. This experiment downloads exact source archives already recorded in
`uv.lock`, verifies size and SHA-256, and builds standard Python 3.13 Android
wheels with cibuildwheel. Build tooling is independently hash locked.

The first matrix covers the six locked packages without a matching published
wheel: `cryptography`, `jiter`, `pydantic-core`, `Pillow`, `psutil`, and
`ruamel.yaml.clib`. Both ARM64 (the iQOO device) and x86_64 (the CI emulator)
are built. A wheel artifact is only build evidence; the separate Hermes import
probe must package and execute it before it is accepted.

Do not upload experimental wheels to a public package index. Promote them only
after their source, build log, ABI tags, hashes and Android import behavior are
reviewed.
