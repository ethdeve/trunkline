# Changelog

English · [中文](CHANGELOG-ZH.md)

This project follows Semantic Versioning. Only released capabilities are recorded here; see [README](README.md) for what comes next.

## v0.1.0 — First public release (2026-11-17)

The first official release of Trunkline. It brings local and cloud models together behind a single gateway, with a ready-to-use management console, security controls, and usage statistics out of the box. Anyone can download an installer, unzip and double-click — no Java or Docker installation required.

### Highlights

- **One gateway for every model**: manage local models (Ollama, vLLM, GPUStack, Xinference, LocalAI) and cloud models in one place, with templates for 13 common providers and custom adapters for any OpenAI-compatible endpoint.
- **More than chat**: besides text generation, support embeddings, reranking, speech, and images.
- **Reliable by design**: automatic failover, per-request retries, rate limiting, and condition-based routing.
- **Secure and controllable**: account plus API-key credentials, role-based permissions, sensitive-data masking, and full call audit trails.
- **Usage at a glance**: per-model token usage, plus visual dashboards for success rate and latency.
- **A console that makes sense**: a three-part interface with light/dark themes and English/Chinese bilingual support.

### Security and privacy

- No built-in default administrator account — create one on first launch through a three-step wizard, avoiding weak default passwords.
- The API accepts a dedicated token header only; unregistered endpoints are denied by default.
- Sensitive content in call records is masked automatically, and the built-in documentation is visible to administrators only.

### User experience

- Ready-to-run installers for Windows, macOS, and Linux, with the runtime bundled — no Java or Docker needed.
- 38 built-in help articles for non-technical users, in English and Chinese; a three-step wizard gets you started.
- Data stays local by default, so you can try it fully offline.

### Changes from the upstream project

This release is forked from the open-source project JAiRouter, with the following Trunkline-specific changes:

- Default database, image name, and documentation branding unified under Trunkline.
- Legacy compatibility and migration logic removed for a simpler configuration.

### Under the hood

- More accurate metering: every call's token usage is fully recorded; unpriced models are flagged separately instead of being counted as zero. Unit prices, bills, and billing are available in the Enterprise Edition.
- More robust deployment: a single, checksum-verified database baseline, with more reliable cross-replica event notifications and isolation checks.
- Quality you can count on: all 4,733 backend tests pass, with both frontend and backend covered by continuous integration.
- The open-source edition keeps only free, foundational capabilities; enterprise features (such as unit-price management, billing, SSO, multi-tenancy, and audit evidence) are provided separately in the commercial edition.
