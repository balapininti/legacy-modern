# 0002: Compute copybook layouts in-house instead of cb2xml or JRecord

Status: accepted. Date: 2026-11-01.

The spec names cb2xml for byte offsets and allows JRecord "if license verified". Verified through the GitHub license API on 2026-11-01: `bmTas/cb2xml` is LGPL-2.1 and `bmTas/JRecord` is LGPL-3.0. Neither meets the constraint "only Apache, MIT or BSD dependencies in the distributable", and the working agreements forbid adding one without asking.

Decision: compute layouts from the data items ProLeap's grammar gives us, in `parsers/.../copybook/CopybookLayout`, with the record decoder driven by that output. Width rules for COMP, COMP-3, COMP-5, signed zoned, SIGN SEPARATE, OCCURS, ODO and REDEFINES are pinned by unit tests with hand-computed sizes and cross-checked against GnuCOBOL `LENGTH OF` output in M7.

Consequence: the layout code is ours to maintain, about the size of one class. The `data_item` note in the spec ("computed from cb2xml") now reads "computed by CopybookLayout".
