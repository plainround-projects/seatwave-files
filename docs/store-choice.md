# Choosing the lab's S3-compatible store

Checked on 12 November 2026. Module 29's lab was first written around MinIO's community edition. On
11 November 2026 its repository was found archived and marked no longer maintained, with the
community edition distributed as source code only and no official image. The course owner decided
on 12 November 2026 to replace it with a maintained S3-compatible store. This page records the
candidates as their own project pages, release pages and registries described them on that day,
what was tested in a container, and the choice.

## What the module needs from the store

Presigned GET and PUT URLs with Signature Version 4 (and the expiry enforced by the store);
multipart upload with listing and aborting of incomplete uploads; a conditional PUT
(`If-None-Match: *`); checksums on upload; object versioning; lifecycle rules, including
`AbortIncompleteMultipartUpload` and expiry by prefix and by tag; refusal of anonymous access and
bucket policies; a listing the reconciliation job can read; and redundancy across several drives
or nodes, with a heal or repair that can be timed after one is lost.

## The candidates, from their own sources

| Project | Latest stable release, date | Maintained (checked 12 November 2026) | License | Official image, arm64 |
|---|---|---|---|---|
| MinIO (community edition) | RELEASE.2025-10-15T17-29-55Z, published 16 October 2025 | repository archived; last push 24 April 2026 | AGPL-3.0 | none pullable (source only) |
| RustFS | 1.0.5, 10 November 2026 | not archived; pushed 12 November 2026; releases 1.0.1 to 1.0.5 since 3 October 2026 | Apache-2.0 | `rustfs/rustfs:1.0.5` on Docker Hub, `sha256:b81d16a90ee25bd9511280127ca1471788c6e874cf0f0da759645f46b2dc1d74`, amd64 and arm64 |
| SeaweedFS | 4.53, 8 November 2026 | not archived; pushed 12 November 2026; 4.49 to 4.53 within 16 days | Apache-2.0 | `chrislusf/seaweedfs:4.53` on Docker Hub, `sha256:ccad23e18639c8b98cee17e7100b95830a06077b56d8e5b006d6dcf8ec5e94a0`, arm64 included |
| Garage | v2.5.0, 24 October 2026 | not archived; pushed 7 November 2026 | AGPL-3.0 | `dxflrs/garage:v2.5.0` on Docker Hub, `sha256:677e5b758e4425a325b811c425c026bf35a4bf42f7ddbaa773de3f4fd779a5a5`, arm64 included |
| Ceph Object Gateway (RGW) | Umbrella v21.2.0 (image of 28 October 2026); Tentacle 20.2.5 of 2 November 2026 | active releases listed on the Ceph releases page | LGPL-2.1 or LGPL-3 | `quay.io/ceph/ceph:v21.2.0`, `sha256:c9e6ed4ac3fcf67b28f38358bb9619dee4d1dfb96ddd4b9b583eee316a15f04d`, amd64 and arm64 |
| Versity S3 Gateway | v1.11.0, 6 November 2026 | not archived; pushed 12 November 2026 | Apache-2.0 | `versity/versitygw:v1.11.0` on Docker Hub, `sha256:8319a530d08d537426e2f855bae5280709795dc73a3f0c347973ef915d6343c5`, amd64 and arm64 |
| Apache Ozone (S3 gateway) | ozone-2.2.2, 30 October 2026 | not archived; pushed 12 November 2026 | Apache-2.0 | not checked |
| Zenko CloudServer | 9.5.5, 12 November 2026 (GitHub) | not archived; pushed 12 November 2026 | Apache-2.0 | `zenko/cloudserver` on Docker Hub last updated January 2023, amd64 only |

Sources: `https://api.github.com/repos/minio/minio`, `https://api.github.com/repos/rustfs/rustfs`,
`https://api.github.com/repos/seaweedfs/seaweedfs`,
`https://git.deuxfleurs.fr/api/v1/repos/Deuxfleurs/garage/releases`,
`https://docs.ceph.com/en/latest/releases/`, `https://raw.githubusercontent.com/ceph/ceph/main/COPYING`,
`https://api.github.com/repos/versity/versitygw`, `https://api.github.com/repos/apache/ozone`,
`https://api.github.com/repos/scality/cloudserver`, and the Docker Hub and quay.io tag APIs
(`https://hub.docker.com/v2/repositories/<name>/tags`, `https://quay.io/api/v1/repository/ceph/ceph/tag/`).

What the projects' own documents say about the features the module needs:

- RustFS: its README's "Feature & Status" table lists versioning, object lock, lifecycle management,
  ILM tiering to a remote S3 store, IAM and policies, distributed mode, bitrot protection, and
  healing and scanner as available (`https://github.com/rustfs/rustfs`).
- SeaweedFS: its wiki lists versioning, bucket policies, conditional headers, presigned URLs, and
  lifecycle rules with `Expiration`, `AbortIncompleteMultipartUpload`, prefix and tag filters, and
  says transitions are rejected with `NotImplemented`; lifecycle deletes run on a worker whose
  default cadence is 24 hours (`https://github.com/seaweedfs/seaweedfs/wiki/Amazon-S3-API`,
  `https://github.com/seaweedfs/seaweedfs/wiki/S3-Lifecycle`).
- Garage: its compatibility page says versioning is missing, bucket policies and ACLs are not
  implemented (Garage has its own per-key permissions), and lifecycle supports only
  `AbortIncompleteMultipartUpload` and `Expiration`
  (`https://garagehq.deuxfleurs.fr/documentation/reference-manual/s3-compatibility/`).
- Ceph RGW: its S3 page lists bucket lifecycle and bucket policies as supported
  (`https://docs.ceph.com/en/latest/radosgw/s3/`). Deploying it needs a Ceph cluster; cephadm
  requires systemd, a container runtime and LVM2 on each host, and an OSD needs an available storage
  device (`https://docs.ceph.com/en/latest/cephadm/install/`,
  `https://docs.ceph.com/en/latest/cephadm/services/osd/`).
- Versity: a gateway that "exposes an S3-compatible API over POSIX filesystems" and other backends;
  redundancy is the backend's (`https://github.com/versity/versitygw`).

## What was tested in a container

Four candidates ran on this laptop (Docker 24.0.6, Apple silicon, arm64 images) with the same
battery of requests, made with the AWS CLI 2.34.23 and a small Signature Version 4 presigner. Ceph
was not run: a cluster on this machine would need privileged containers or raw devices for its
OSDs, which the course does not use. Ozone and CloudServer were not short-listed.

| Request | RustFS 1.0.5 | SeaweedFS 4.53 | Garage v2.5.0 | Versity v1.11.0 |
|---|---|---|---|---|
| PUT with a SHA-256 checksum | stored, checksum returned | stored, checksum returned | stored, checksum returned | stored, checksum returned |
| PUT with a wrong checksum | 400 BadDigest | 400 BadDigest | 400 InvalidDigest | 400 BadDigest |
| anonymous GET and LIST | 403, 403 | 403, 403 | 403, 403 | 403, 403 |
| presigned GET in time, then after a 2 s expiry | 200, 403 | 200, 403 | 200, 400 | 200, 403 |
| presigned PUT with the signed type, with another type | 200, 403 | 200, 403 | 200, 403 | 200, 403 |
| PUT `If-None-Match: *` on an existing key | 412 PreconditionFailed | 412 PreconditionFailed | accepted, object replaced | 412 PreconditionFailed |
| incomplete uploads listed, then aborted | 1 open, then 0 | 1 open, then 0 | 1 open, then 0 | 1 open, then 0 |
| complete naming a part that never arrived | 400 InvalidPart | 400 InvalidPart | 400 InvalidRequest | 400 InvalidPart |
| versioning: two PUTs, versions listed | 2 | 2 | not implemented | 2 |
| lifecycle with a transition to STANDARD_IA | refused ("invalid tier") | refused (NotImplemented) | accepted | refused (NotImplemented) |
| lifecycle with abort, prefix expiry and tag expiry | accepted, read back | accepted, read back | accepted, read back | refused (NotImplemented) |
| bucket policy put, policy status | accepted, not public | accepted, not public | not implemented | accepted, not public |
| ListObjectsV2 | 4 keys | 4 keys | 4 keys | 4 keys |

Redundancy, tested on the two stores that covered the most of the battery:

- RustFS, four nodes with one volume each (an erasure set of 4 with parity 2, as `rc admin info
  cluster` reported): 203 objects (52.6 MB) written; one node and its volume removed; all 203 read
  back with their SHA-256; the node restarted on a blank volume and healed on its own, its drive
  holding the same files as a peer about 9 s after its start. With 2 of the 4 nodes gone, the store
  answered 503 to reads and writes. One node with four volumes was refused at start-up: "local
  erasure endpoints must use distinct physical disks" (all four volumes are on the virtual
  machine's one disk); the setting that skips the check is for testing only, so the lab uses four
  nodes.
- SeaweedFS, three volume servers with replication `001`: the same 203 objects; one volume server
  and its volume removed; all 203 read back; after a blank replacement started, `volume.fix.replication`
  in `weed shell`, run by hand, copied the under-replicated volumes in 30.3 s.

Found while building with RustFS 1.0.5 and the AWS SDK for Java 2.58.0: with the SDK's default
`aws-chunked` request bodies, every PUT and part upload that carried a SHA-256 checksum was answered
400 BadDigest; with chunked encoding turned off (`S3Configuration.chunkedEncodingEnabled(false)`),
the same requests were accepted and a damaged part was still refused.

## The choice

RustFS 1.0.5. It answered every request of the battery as S3 does except the transition to a
storage class, which it refuses because it has no storage classes (its tiering goes to a remote S3
store). It keeps objects as erasure-coded shards across drives or nodes and heals a lost one on its
own, which is what Sections 5e and 6d of the module teach and Lab 3 measures; it also lists object
lock as available, which Module 40 needs. SeaweedFS covered the same requests; its default
redundancy is whole-volume replication repaired by an operator command, and its erasure coding
applies to sealed volumes through `weed shell`. Garage lacks versioning, bucket policies and the
conditional PUT. Versity has no lifecycle rules and no redundancy of its own.

What the lab's store does not do, and the module keeps as `[not run]`: lifecycle transitions to
`STANDARD_IA` and `GLACIER_IR` ("the lab's store does not implement it; a cloud object store does").
What the lab could not wait for: the 7-day lifecycle rules acting on their own; the lab shows them
accepted and read back, and makes the same abort decision on demand with `StaleUploadSweeper`.
