# 0001: Vendor ProLeap rebuilt on ANTLR 4.13.2

Status: accepted. Date: 2026-11-01.

ProLeap COBOL parser (MIT, verified from `uwol/proleap-cobol-parser` LICENSE at tag v2.4.0, commit b045d80) is not on Maven Central. It targets ANTLR 4.7, whose serialized ATN format cannot load on the 4.10+ runtime our own grammars use, and one JVM holds one runtime.

Decision: rebuild from the pinned commit with `third_party/patches/proleap-v2.4.0-antlr-4.13.2.patch` (`scripts/build-third-party.sh`) and commit the jar to the file repository `third_party/repo`. The bump also removes Guava from ProLeap's transitive tree, so we declare it directly.

Measured: rebuilt parser accepted 31 of 31 CardDemo `app/cbl` programs in strict mode, about 11 s in total on one thread.
