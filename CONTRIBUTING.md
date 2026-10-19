# Contributing to LiberPet

Bug reports, device requests, feature requests and pull requests are welcome. Please keep changes focused: one problem, device or feature per issue and pull request.

## Report a bug or request a feature

Use the repository's issue forms and include enough detail to reproduce or understand the request. Remove passwords, Home Assistant tokens and URLs, device identifiers, camera details, pet data and other personal information from diagnostics, logs and screenshots.

For security vulnerabilities, do not open a public issue. Follow [SECURITY.md](SECURITY.md).

## Make a change

1. Open or link an issue for non-trivial work so the intended behaviour is clear.
2. Create a branch from `main`.
3. Add or update tests for behaviour changes and run:

   ```bash
   ./gradlew testDebugUnitTest
   ```

4. If the change is user-facing, attach before-and-after screenshots to the pull request. Use a short video when motion or a multi-step interaction cannot be shown clearly in still images. Say “No visual change” when that is genuinely the case.
5. For device-specific changes, list the exact model, integration version and Home Assistant entities used. Do not claim a real-device test unless it was actually performed.

All checks must pass. Generated or AI-assisted contributions are welcome, but the author remains responsible for understanding, testing and clearly describing the result.

Automation and contributors may prepare pull requests, but must never merge them. The maintainer reviews and explicitly approves every merge into `main`.
