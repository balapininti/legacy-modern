# 0003: Log4j2 instead of Logback, and a license gate with named exceptions

Status: accepted. Date: 2026-11-01.

Spring Boot defaults to Logback (EPL-1.0 / LGPL-2.1). We use `spring-boot-starter-log4j2` (Apache-2.0) and the enforcer plugin bans `ch.qos.logback:*`.

`scripts/license-check.py` fails the build when a distributable dependency is outside Apache, MIT or BSD (and permissive equivalents such as MIT-0 and PostgreSQL) unless named in `scripts/license-exceptions.txt`. That file holds dependencies which have no permissive replacement because the Spring stack requires them, each with a reason.

JavaParser is dual licensed and is used under its Apache-2.0 option.

The owner should review `scripts/license-exceptions.txt`: a strict reading of the constraint would rule out Spring itself, because `jakarta.*` API jars are EPL-2.0 with GPL-2.0 + Classpath Exception.
