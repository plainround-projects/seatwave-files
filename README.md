# seatwave-files

SeatWave's ticket PDFs and organizer posters in S3-compatible object storage. This is the project of Module 29, "Object storage", of the course System Design Fundamentals: pre-signed upload and download URLs (a teaching signer and the real Signature Version 4 from the AWS SDK), multipart upload with checksums and the clean-up of abandoned uploads, content addressing, lifecycle rules and their five-year cost, a table `stored_objects` and the hourly job that keeps it and the bucket in step, and a node-loss drill on a four-node erasure-coded store.

## Prerequisites

- JDK 25 (the Gradle toolchain finds it; built with Eclipse Temurin 25.0.2, where 25.0.5+8 is the current release).
- For the stack, the stack tests and the drill only: Docker with Compose (run with Docker 24.0.6 and Compose v2.23.0), `openssl`, `curl` and `python3`. `./gradlew check` needs no Docker.

## Dependencies (each checked on Maven Central, Docker Hub or the Gradle site on 12 November 2026; OSV listed no vulnerability)

| What | Coordinates | Version | License | Why the JDK alone is not enough |
|---|---|---|---|---|
| Gradle wrapper | `gradle-9.9.1-bin.zip`, both checksums compared | 9.9.1 | Apache-2.0 | one build tool version for every reader |
| AWS SDK for Java v2 | `software.amazon.awssdk:bom`, `software.amazon.awssdk:s3` | 2.58.0 | Apache-2.0 | the JDK has no S3 client and no Signature Version 4 |
| result4j | `com.github.sviperll:result4j` | 1.2.0 | BSD-3-Clause | settings problems are returned as a value |
| PostgreSQL JDBC driver | `org.postgresql:postgresql` (runtime) | 42.7.14 | BSD-2-Clause | the JDK has the JDBC interfaces, not the driver |
| Logback | `ch.qos.logback:logback-classic` (runtime) | 1.6.6 | EPL-1.0 or LGPL-2.1 | the SDK logs through SLF4J and needs a backend |
| JUnit | `org.junit:junit-bom` | 6.1.3 | EPL-2.0 | the test runner |
| AssertJ | `org.assertj:assertj-core` | 3.27.7 | Apache-2.0 | readable assertions |
| Spotless, google-java-format | `com.diffplug.spotless`, `com.google.googlejavaformat:google-java-format` | 8.11.0, 2.0.0 | Apache-2.0 | the formatter |
| JaCoCo | `org.jacoco` | 0.8.16 | EPL-2.0 | line coverage, floor 0.80 |
| RustFS image | `rustfs/rustfs:1.0.5@sha256:b81d16a90ee25bd9511280127ca1471788c6e874cf0f0da759645f46b2dc1d74` | 1.0.5 | Apache-2.0 | the S3-compatible store (why this one: `docs/store-choice.md`) |
| PostgreSQL image | `postgres:18.6@sha256:e3d24f6906d0760bf4da0dae872415391ededefad23fd471c47dc53b0ddf3c42` | 18.6 | PostgreSQL License | the table `stored_objects` |

## Commands and their real output

Build and test (no Docker): `./gradlew clean check`

```
> Task :spotlessCheck
> Task :check
> Task :jacocoTestReport

BUILD SUCCESSFUL in 2s
10 actionable tasks: 10 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.9.1/userguide/configuration_cache_enabling.html
exit status 0
```

```
check (unit tests; programs and store adapters excluded): 90.6 percent of lines (326 of 360)
check and stack tests together (programs excluded): 83.0 percent of lines (538 of 648)
```

The four JDK-only programs: `./gradlew -q presignRun`, `multipartRun`, `integrityRun`, `costRun`. For example `./gradlew -q presignRun`:

```
signed at 2026-11-11T10:00:00Z, signatures redacted
upload URL:   https://files.seatwave.example/seatwave-media/incoming/event-1/019a7300-5c2e-7d41-9a3b-2f6c8e1d4b70.jpg?X-Sw-Expires=1794391500&X-Sw-Content-Type=image%2Fjpeg&X-Sw-Content-Length=4200000&X-Sw-Signature=<64 hex digits, redacted>
download URL: https://files.seatwave.example/seatwave-tickets/tickets/1/019a7302-8e11-7b5c-a4d2-6e9f0c3b7a15.pdf?X-Sw-Expires=1794391260&X-Sw-Signature=<64 hex digits, redacted>

#   request                                        at      verdict
1   organizer PUTs the poster as signed            +12 s   ACCEPTED PUT incoming/event-1/019a7300-5c2e-7d41-9a3b-2f6c8e1d4b70.jpg
2   the same PUT, after the 300 s lifetime         +301 s  EXPIRED: expired 1 s ago
3   path edited to another event's prefix          +12 s   BAD_SIGNATURE: signature does not match PUT /seatwave-media/incoming/event-2/019a7300-5c2e-7d41-9a3b-2f6c8e1d4b70.jpg
4   expiry edited one day later                    +400 s  BAD_SIGNATURE: signature does not match PUT /seatwave-media/incoming/event-1/019a7300-5c2e-7d41-9a3b-2f6c8e1d4b70.jpg
5   a 20,000,000-byte body on the 4.2 MB grant     +12 s   CONTENT_MISMATCH: signed image/jpeg of 4200000 bytes, sent image/jpeg of 20000000 bytes
6   an HTML body on the JPEG grant                 +12 s   CONTENT_MISMATCH: signed image/jpeg of 4200000 bytes, sent text/html of 4200000 bytes
7   fan GETs the ticket through the redirect       +1 s    ACCEPTED GET tickets/1/019a7302-8e11-7b5c-a4d2-6e9f0c3b7a15.pdf
8   someone else GETs the same ticket URL          +40 s   ACCEPTED GET tickets/1/019a7302-8e11-7b5c-a4d2-6e9f0c3b7a15.pdf
9   the ticket URL used to PUT a file              +5 s    BAD_SIGNATURE: signature does not match PUT /seatwave-tickets/tickets/1/019a7302-8e11-7b5c-a4d2-6e9f0c3b7a15.pdf

asking for an 8-day ticket URL: refused, lifetime PT192H exceeds the maximum of PT15M
```

The stack (every script is a dry run unless `--run`, and asks before it changes anything unless `--yes`):

- `scripts/stack.sh up --run`: credentials into `.secrets/`, four RustFS nodes (S3 API on 127.0.0.1:22900) and PostgreSQL 18.6 (127.0.0.1:22920), then the buckets (`./gradlew -q setupBuckets`).
- `./gradlew -q ticketLinks`, `./gradlew -q multipartStore`, `./gradlew -q reconcile`: the store programs.
- `scripts/stack.sh test --run`: up, `./gradlew stackTest stackCoverage`, down, on every path.
- `scripts/node-loss-drill.sh --size small|large|huge --runs N --run`: the node-loss drill, each run on a fresh stack.
- `scripts/stack.sh down --run`: `down -v`, the credentials removed, and a check that nothing of the project is left.

`./gradlew -q ticketLinks` on the running stack:

```
$ ./gradlew -q ticketLinks
ticket: 200,000 bytes, SHA-256 3c069ce456e5e2919cf4c089749cd9a15f558408de27075c62190bcca91a4e68
  PUT through the signed upload URL        200
  the same PUT again (If-None-Match: *)    412 PreconditionFailed
  a PUT that sends text/html instead       403 SignatureDoesNotMatch
  GET through the signed 60 s ticket URL   200, bytes match: true
  anonymous GET of the same key            403 AccessDenied
  GET through a 1 s URL at once            200
  the same 1 s URL, 2 s later              403 AccessDenied
exit status 0
```

`./gradlew -q reconcile`:

```
$ ./gradlew -q reconcile
dry run:
  missing (page someone):     ...0002.pdf
  mismatched:                 (none)
  orphans to delete:          ...0003.pdf
  pending to abandon:         ...0004.pdf
  deletes to finish:          ...0005.pdf
applied:
  missing (page someone):     ...0002.pdf
  mismatched:                 (none)
  orphans to delete:          ...0003.pdf
  pending to abandon:         ...0004.pdf
  deletes to finish:          ...0005.pdf
  objects deleted:            ...0003.pdf ...0005.pdf
  rows removed:               ...0005.pdf ...0004.pdf
next run:
  missing (page someone):     ...0002.pdf
  mismatched:                 (none)
  orphans to delete:          (none)
  pending to abandon:         (none)
  deletes to finish:          (none)
healthy ticket still in the bucket: true
exit status 0
```

The drill at 4,040,000,000 bytes (`scripts/node-loss-drill.sh --size huge --runs 2 --run --yes`), on a laptop, under the course's measurement lock:

```
$ build/lock.sh scripts/node-loss-drill.sh --size huge --runs 2 --run --yes
lock taken 16:45:13Z, load: 5.41 6.12 7.02
plan: 2 run(s) of the node-loss drill on seatwave-files, 400 objects, 4040000000 bytes each run
  each run: stack up, write, read; lose node 3; heal it (timed); read; lose node 1; read;
  lose node 2 (2 of 4 left): read and write; stack down -v
run 1 of 2 (5.41 6.12 7.02, 1, 5 and 15-minute load)
  before the loss (4 of 4 nodes): 400 of 400 objects read back with their SHA-256
  node 3 and its drive lost (3 of 4): 400 of 400 objects read back with their SHA-256
  heal: node 3 healthy 3.2 s after its start; its drive matched node 4's (600 files, 1976848 KB) 12.8 s later
  after the heal, read through node 3: 400 of 400 objects read back with their SHA-256
  node 1 lost as well, after the heal (3 of 4): 400 of 400 objects read back with their SHA-256
  node 2 lost too (2 of 4): 0 of 400 objects read back with their SHA-256
  a write with 2 of 4 nodes: refused
run 2 of 2 (6.05 6.11 6.88, 1, 5 and 15-minute load)
  before the loss (4 of 4 nodes): 400 of 400 objects read back with their SHA-256
  node 3 and its drive lost (3 of 4): 400 of 400 objects read back with their SHA-256
  heal: node 3 healthy 2.9 s after its start; its drive matched node 4's (600 files, 1976848 KB) 11.5 s later
  after the heal, read through node 3: 400 of 400 objects read back with their SHA-256
  node 1 lost as well, after the heal (3 of 4): 400 of 400 objects read back with their SHA-256
  node 2 lost too (2 of 4): 0 of 400 objects read back with their SHA-256
  a write with 2 of 4 nodes: refused
run 1: huge, 4040000000 bytes, 400 objects, 1976848 KB per drive, heal 12.8 s
run 2: huge, 4040000000 bytes, 400 objects, 1976848 KB per drive, heal 11.5 s
lock released 16:49:18Z, load: 8.36 6.74 7.02
```

## The Compose path

`compose.yaml` runs four RustFS nodes with one named volume each (one erasure set of 4, parity 2) and PostgreSQL 18.6, under the project name `seatwave-files`, ports on 127.0.0.1 only, no privileged container, read-only root file systems, a memory limit on every service. The store's root key and the database passwords are Compose secrets generated into `.secrets/` (ignored by git) and removed by `down`. RustFS refuses an erasure set of four folders on one disk, which is what four Docker volumes are; four nodes with one volume each is a layout it accepts, and the drill loses a node with its volume.

## Project structure

```
src/main/java/io/plainround/seatwave/files/   the JDK-only models and programs
src/sdk/java/io/plainround/seatwave/files/    the AWS SDK, JDBC and store code, and its programs
src/test/java/io/plainround/seatwave/files/   unit tests, and the tests tagged "stack"
config/store.properties                        where the store and the database are
db/                                           stored_objects and the owner role
lifecycle/seatwave-media.json                 the S3 lifecycle rules
scripts/                                      stack.sh and node-loss-drill.sh
docs/store-choice.md                          the comparison of S3-compatible stores
```

## What is simplified

- The listing for the reconciliation job is `ListObjectsV2` with a HEAD per object; at SeatWave's scale it is the store's daily inventory report.
- The lab's four store volumes share one disk, so the drill proves the mechanism and gives a method, not production's rebuild time.
- The lifecycle transition to `STANDARD_IA` is S3-only; the lab's store has no storage classes.
- The 7-day lifecycle rules are applied and read back but not waited for; `StaleUploadSweeper` makes the abort decision on demand.
- `StoredObjectsTable` opens a connection per call; an hourly job needs no pool.

## No CI on purpose

There is no `.github/` folder and no workflow: the course verifies every project on the author's machine and records the outputs in the module.
