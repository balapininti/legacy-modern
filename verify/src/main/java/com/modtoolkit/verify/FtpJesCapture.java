package com.modtoolkit.verify;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;

/** Through the z/OS FTP server's JES interface. For sites where z/OSMF is not available. */
public final class FtpJesCapture implements CaptureClient {
    private static final Pattern JOBID = Pattern.compile("\\b(?:JOB|J|TSU|STC)\\d{3,}\\b");
    private static final Pattern RC = Pattern.compile("RC=(\\d+)|ABEND=(\\S+)|(\\d+) spool files", Pattern.CASE_INSENSITIVE);

    private final String host;
    private final int port;
    private final String user;
    private final String password;

    public FtpJesCapture(String host, int port, String user, String password) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
    }

    private FTPClient connect() throws IOException {
        FTPClient f = new FTPClient();
        f.setConnectTimeout(30_000);
        f.connect(host, port);
        if (!FTPReply.isPositiveCompletion(f.getReplyCode()) || !f.login(user, password)) {
            f.disconnect();
            throw new IOException("FTP login to " + host + " failed");
        }
        f.enterLocalPassiveMode();
        return f;
    }

    private static void close(FTPClient f) {
        try {
            f.logout();
            f.disconnect();
        } catch (IOException ignored) {
            // The transfer already succeeded or failed by now.
        }
    }

    @Override
    public String submit(String jcl) throws Exception {
        FTPClient f = connect();
        try {
            f.sendSiteCommand("FILETYPE=JES");
            if (!f.storeFile("TKCAPT", new ByteArrayInputStream(jcl.getBytes(StandardCharsets.ISO_8859_1)))) throw new IOException("submit refused: " + f.getReplyString());
            Matcher m = JOBID.matcher(f.getReplyString());
            if (!m.find()) throw new IOException("no job id in the reply: " + f.getReplyString());
            return m.group();
        } finally {
            close(f);
        }
    }

    @Override
    public String waitFor(String jobId, Duration timeout) throws Exception {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            FTPClient f = connect();
            try {
                f.sendSiteCommand("FILETYPE=JES");
                f.sendSiteCommand("JESJOBNAME=*");
                for (String line : f.listNames(jobId) == null ? new String[0] : f.listNames(jobId)) {
                    if (line.contains("OUTPUT")) {
                        Matcher m = RC.matcher(line);
                        return m.find() ? m.group() : "OUTPUT";
                    }
                }
            } finally {
                close(f);
            }
            Thread.sleep(2000);
        }
        throw new IOException("job " + jobId + " did not finish in " + timeout);
    }

    @Override
    public void downloadDataset(String dsn, Path to) throws Exception {
        FTPClient f = connect();
        try {
            f.setFileType(FTP.BINARY_FILE_TYPE);
            Files.createDirectories(to.toAbsolutePath().getParent());
            try (var out = Files.newOutputStream(to)) {
                if (!f.retrieveFile("'" + dsn + "'", out)) throw new IOException("download of " + dsn + " failed: " + f.getReplyString());
            }
        } finally {
            close(f);
        }
    }

    @Override
    public String spool(String jobId) throws Exception {
        FTPClient f = connect();
        try {
            f.sendSiteCommand("FILETYPE=JES");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            f.retrieveFile(jobId + ".x", b);
            return b.toString(StandardCharsets.ISO_8859_1);
        } finally {
            close(f);
        }
    }
}
