# Response to the PR #3 review (2026-11-12)

1. **Settings inventory and rendered board missing** → added `docs/SETTINGS_INVENTORY.md` and `docs/design/brand/board.html` (open it in a browser to render the board; the PNG render was sent in chat and is in the A0r6 zip at `docs/design/brand/board.png`).

2. **Signing key only in the Actions cache** → agreed: a cache miss would silently create a new key. A0r6 adds an APK identity step to `ci-build.sh`: package, versionName/versionCode and signer SHA-256 are read from the built APK with apksigner/aapt2 and shown in the run summary; the run fails when `.github/signing/expected-debug-cert-sha256.txt` exists and differs. Next, once the installed build is known from the Settings footer: pin that certificate, and make its key durable (build #4/#5 installed → export the cached key once into the repository secret and stop creating keys while a pin exists; Codex build installed → build with that branch's keystore). That needs one workflow edit by the owner, prepared after the answer.

3. **Allocation gate used the lowest round (1184 / 0 / 0)** → reverted to "every round < 1024 bytes". Warm-up is now 20 000 buffers with the same sizes and an alternating parameter change; each measured round is split into 10 blocks of 500 buffers so a one-off allocation is located; direct-buffer growth is measured and must be 0. The earlier 1184-byte round stays in the history; its cause is open until the next CI run reports the block.

A0r6: versionName 0.2.2-a0, versionCode 4. The A0 phone gate is still open; no A1 work is included.
