# Build Support

This directory owns repository-wide build policy.

- `stratus-bom` owns approved third-party library versions.
- `stratus-build-parent` owns Java, compiler, test, packaging, and quality-plugin versions and defaults.

Deployable child modules import this policy through `stratus-build-parent`. Child POMs must not pin dependency or build-plugin versions. A component-specific version exception belongs in the BOM or build parent with compatibility evidence, not inline in a child module.
