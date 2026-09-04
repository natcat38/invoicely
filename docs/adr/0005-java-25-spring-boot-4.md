# ADR-0005 — Java 25 and Spring Boot 4.1 instead of Java 21 / Boot 3

**Status:** accepted · **Date:** 2026-09-05 · **Decided by:** Natalie

> Numbered 0005 because ADR-0001..0004 are reserved by the Tech Scope for
> decisions that land in Tasks 2, 4 and 5. This one simply arrived first.

## Context

The scope docs pinned Java 21 / Spring Boot 3.x, written when those were
current. At Task 1 (2026-09-05) Spring Initializr no longer offers Boot 3 at
all — its oldest option is 4.0.8, default 4.1.1 — so staying on 3.x would have
meant hand-writing the POM against a line that is past its OSS support window.
Both JDK 21 and JDK 25 are installed locally.

## Decision

Build on **Java 25 + Spring Boot 4.1.1**.

- Boot 4 is the only version Initializr still generates, and it is the version
  a reviewer would expect a project started in late 2026 to use.
- Java 25 is the current LTS. `JAVA_HOME` must point at the JDK 25 install;
  CI pins `java-version: 25`.

## Consequences

- Most Spring tutorials and courses still target Boot 3. The differences that
  bite in this codebase are starter renames — `spring-boot-starter-webmvc`
  (not `-web`), `spring-boot-starter-security-oauth2-resource-server`, and a
  `-test` companion starter per starter. Boot 3 material is otherwise
  transferable; treat naming mismatches as version skew, not as your mistake.
- Scope docs, README and Build Sources were updated to say Java 25 / Boot 4.
- Anyone building with JDK 21 on `JAVA_HOME` gets
  `error: release version 25 not supported` — repoint `JAVA_HOME`, don't
  downgrade the POM.
