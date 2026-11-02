package com.modtoolkit.parsers.classify;

public enum MemberType { COBOL, COPYBOOK, JCL, PROC, BMS, DDL, CSD, PLI, ASSEMBLER, EASYTRIEVE, REXX, CLIST, CONTROL, UNKNOWN;
    public String db() { return name().toLowerCase(); }
    /** Parsed in full in v1; the rest are inventoried at member level only. */
    public boolean parsed() { return this == COBOL || this == COPYBOOK || this == JCL || this == PROC || this == BMS || this == DDL || this == CSD; }
}
