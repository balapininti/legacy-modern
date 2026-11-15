package com.modtoolkit.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** No LPAR here: these prove the commands sent and the parsing, against a stand-in zowe executable. */
class CaptureClientsTest {
    @TempDir Path dir;

    private Path fakeZowe() throws Exception {
        Path f = dir.resolve("zowe");
        Files.writeString(f, """
                #!/bin/sh
                echo "$@" >> "$(dirname "$0")/calls.log"
                echo "$ZOWE_OPT_HOST|$ZOWE_OPT_USER|$ZOWE_OPT_PASSWORD|$ZOWE_OPT_REJECT_UNAUTHORIZED" >> "$(dirname "$0")/env.log"
                case "$1 $2 $3" in
                  "zos-jobs submit stdin") cat > "$(dirname "$0")/submitted.jcl"; echo '{"success":true,"data":{"jobid":"JOB01234"}}' ;;
                  "zos-jobs view job-status-by-jobid")
                     c="$(dirname "$0")/count"; n=$(cat "$c" 2>/dev/null || echo 0); echo $((n+1)) > "$c"
                     if [ "$n" = 0 ]; then echo '{"success":true,"data":{"status":"ACTIVE","retcode":null}}'; else echo '{"success":true,"data":{"status":"OUTPUT","retcode":"CC 0000"}}'; fi ;;
                  "zos-files download data-set")
                     while [ $# -gt 0 ]; do if [ "$1" = "--file" ]; then printf 'BYTES' > "$2"; fi; shift; done
                     echo '{"success":true,"data":{}}' ;;
                  "zos-jobs view all-spool-content") echo 'JESMSGLG line' ;;
                  *) echo '{"success":false,"message":"unexpected"}' ;;
                esac
                """);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"));
        return f;
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the stand-in Zowe CLI is a POSIX shell script; the Windows zowe.cmd path is covered by the batch-file test below")
    void zoweSubmitsWaitsAndDownloadsInBinary() throws Exception {
        ZoweCapture z = new ZoweCapture(fakeZowe().toString(), "lpar", "u", "s3cr&t");
        String job = z.submit("//TK JOB\n");
        assertThat(job).isEqualTo("JOB01234");
        assertThat(Files.readString(dir.resolve("submitted.jcl"))).isEqualTo("//TK JOB\n");
        assertThat(z.waitFor(job, Duration.ofSeconds(30))).isEqualTo("CC 0000");
        Path out = dir.resolve("o dir/DS.bin");
        z.downloadDataset("TK.CAPTURE.OUT", out);
        assertThat(Files.readString(out)).isEqualTo("BYTES");
        assertThat(z.spool(job)).contains("JESMSGLG");
        String calls = Files.readString(dir.resolve("calls.log"));
        assertThat(calls).contains("--binary").contains("job-status-by-jobid JOB01234").contains("--rfj");
        // Connection settings and the secret travel in the child's environment, never on its command line.
        assertThat(calls).doesNotContain("lpar").doesNotContain("s3cr&t").doesNotContain("--password").doesNotContain("--host");
        assertThat(Files.readAllLines(dir.resolve("env.log"))).isNotEmpty().allMatch(l -> l.equals("lpar|u|s3cr&t|false"));
    }

    @Test
    void aBatchFileCommandRefusesArgumentsCmdWouldInterpret() throws Exception {
        assertThat(ZoweCapture.batchFile("zowe.cmd")).isTrue();
        assertThat(ZoweCapture.batchFile("C:\\npm\\ZOWE.BAT")).isTrue();
        assertThat(ZoweCapture.batchFile("/usr/local/bin/zowe")).isFalse();
        ZoweCapture.checkBatchArgument("TK.CAPTURE.OUT");
        ZoweCapture.checkBatchArgument("C:\\Users\\Bala Krishna\\caps\\DS.bin");
        for (String bad : new String[] {"a&b", "a|b", "a<b", "a>b", "a^b", "%PATH%", "a\"b", "a!b", "a\nb"}) {
            assertThatThrownBy(() -> ZoweCapture.checkBatchArgument(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
