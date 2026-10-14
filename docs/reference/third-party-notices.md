# Third-party notices

Components that are bundled with Abada in source or dependency form, with the
licence they carry. Dependency trees (Maven, npm) have their own licence
metadata; this page lists what needs a notice of its own.

| Component | Where | Licence | Notice |
| --- | --- | --- | --- |
| [cuelume](https://www.npmjs.com/package/cuelume) 0.2.8, interaction sounds synthesized with the Web Audio API | Studio dependency (`studio/package.json`); a copy is vendored in `docker/keycloak/themes/abada/login/resources/js/vendor/cuelume/` for the Keycloak login theme | MIT | Copyright (c) 2026 Daniel Belyi. The licence text is kept next to the vendored copy (`.../vendor/cuelume/LICENSE`) and is included in the npm package. |

Abada itself is licensed under the GNU AGPL-3.0 (`LICENSE`); the worker SDK is
Apache-2.0 (`sdk/java/LICENSE`).
