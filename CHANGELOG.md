# Changelog

All notable changes to this project are written here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-12

The first build of Module 29's code seed (17 files), run for the first time with Gradle, against a
real S3-compatible store and against PostgreSQL 18.6.

### Added

- The Gradle wrapper (9.9.1, both checksums compared), the version catalog, lockfiles, Spotless with
  google-java-format, JaCoCo with a line floor of 0.80, one `JavaExec` task per program, README,
  ARCHITECTURE, this changelog, the license, and `docs/store-choice.md`.
- `compose.yaml`: four RustFS 1.0.5 nodes with one volume each (S3 API on 127.0.0.1:22900) and
  PostgreSQL 18.6 (127.0.0.1:22920) with `db/V1__stored_objects.sql` run as its owner role
  (`db/init/10-owner.sh`); `scripts/stack.sh` (`up`, `down`, `test`, `status`; a dry run unless
  `--run`); `scripts/node-loss-drill.sh` (repeated runs, the heal timed).
- The `sdk` source set: `StoreSettings`, `ObjectStore`, `StoreHttp`, `StoredObjectsTable`,
  `ReconcileJob`, `StaleUploadSweeper`, and the programs `SetupBucketsCli`, `TicketLinksCli`,
  `MultipartLostPartCli`, `ReconcileCli`; `S3UrlIssuer.signedUpload`, which returns the headers a
  client must send with a signed PUT.
- Tests: `MultipartUploadTest`, `ContentAddressTest`, `StoreSettingsTest`, `StaleUploadSweeperTest`,
  `LifecycleFileTest`, three more `ObjectReconcilerTest` cases, and the stack tests
  `StoreAccessStackTest`, `ReconcileJobStackTest`, `StaleUploadStackTest`.

### Changed

- The lab's store is RustFS 1.0.5 instead of MinIO's community edition, which the seed built from
  its archived source (`docs/store-choice.md`).
- The AWS SDK for Java from 2.57.13 to 2.58.0, the release of 12 November 2026.
- `S3UrlIssuer`: compiled and run; its comments name the lab's store.
