package com.modtoolkit.discovery.modern;

import static org.assertj.core.api.Assertions.assertThat;

import com.modtoolkit.discovery.modern.LinkCandidates.Commit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The commit history that the commit_ref signal reads, through JGit, with no git executable needed. */
class CommitLogTest {
    @TempDir Path dir;

    private int clock = 1_700_000_000;

    private RevCommit commit(Git git, String message) throws Exception {
        PersonIdent who = new PersonIdent("t", "t@example.com", java.time.Instant.ofEpochSecond(clock += 60), java.time.ZoneOffset.UTC);
        return git.commit().setMessage(message).setAuthor(who).setCommitter(who).setSign(false).call();
    }

    private void write(Path repo, String path, String text) throws Exception {
        Path f = repo.resolve(path);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    /** Root, change, rename, delete, a side branch and its merge. */
    private Path history() throws Exception {
        Path repo = dir.resolve("repo");
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            write(repo, "README.md", "start\n");
            write(repo, "src/main/java/com/acme/Interest.java", "class Interest {}\n" + "// filler line\n".repeat(20));
            git.add().addFilepattern(".").call();
            commit(git, "chore: start");

            write(repo, "src/main/java/com/acme/Interest.java", "class Interest { int rate; }\n" + "// filler line\n".repeat(20));
            write(repo, "src/main/java/com/acme/Account.java", "class Account {}\n");
            git.add().addFilepattern(".").call();
            commit(git, "Port CBACT04C interest\ncalculation to Spring Batch\n\nBody cites COACTVWC.\nSecond line.\n");

            Files.move(repo.resolve("src/main/java/com/acme/Interest.java"), repo.resolve("src/main/java/com/acme/InterestJob.java"));
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();
            commit(git, "Rename for CBACT04C");

            Files.delete(repo.resolve("README.md"));
            git.add().setUpdate(true).addFilepattern(".").call();
            commit(git, "Drop readme");

            git.checkout().setCreateBranch(true).setName("side").call();
            write(repo, "src/main/java/com/acme/Side.java", "class Side {}\n");
            git.add().addFilepattern(".").call();
            commit(git, "Side work for CBTRN02C");
            git.checkout().setName("main").call();
            write(repo, "src/main/java/com/acme/Main.java", "class Main {}\n");
            git.add().addFilepattern(".").call();
            commit(git, "Main work");
            MergeResult m = git.merge().include(git.getRepository().resolve("side")).setCommit(false).call();
            assertThat(m.getMergeStatus().isSuccessful()).isTrue();
            commit(git, "Merge side for CBTRN02C");
        }
        return repo;
    }

    @Test
    void readsEveryCommitWithItsMessageAndChangedFiles() throws Exception {
        Map<String, Commit> bySubject = LinkCandidates.commits(history()).stream().collect(Collectors.toMap(Commit::subject, Function.identity()));
        assertThat(bySubject).hasSize(7);
        assertThat(bySubject.get("chore: start").files()).containsExactlyInAnyOrder("README.md", "src/main/java/com/acme/Interest.java");
        Commit port = bySubject.get("Port CBACT04C interest calculation to Spring Batch");
        assertThat(port.message()).isEqualTo("Port CBACT04C interest calculation to Spring Batch Body cites COACTVWC.\nSecond line.\n");
        assertThat(port.files()).containsExactlyInAnyOrder("src/main/java/com/acme/Account.java", "src/main/java/com/acme/Interest.java");
        assertThat(port.hash()).hasSize(40);
        assertThat(bySubject.get("Rename for CBACT04C").files()).containsExactly("src/main/java/com/acme/InterestJob.java");
        assertThat(bySubject.get("Drop readme").files()).containsExactly("README.md");
        assertThat(bySubject.get("Side work for CBTRN02C").files()).containsExactly("src/main/java/com/acme/Side.java");
        assertThat(bySubject.get("Merge side for CBTRN02C").files()).isEmpty();
        assertThat(bySubject.get("Drop readme").message()).isEqualTo("Drop readme ");
    }

    @Test
    void noRepositoryOrNoCommitsIsAnEmptyHistory() throws Exception {
        assertThat(LinkCandidates.commits(dir)).isEmpty();
        Git.init().setDirectory(dir.resolve("empty").toFile()).call().close();
        assertThat(LinkCandidates.commits(dir.resolve("empty"))).isEmpty();
    }

    /** Differential: where a git executable exists, JGit sees exactly what `git log --name-only` printed before. */
    @Test
    void matchesGitLogNameOnly() throws Exception {
        Path repo = history();
        ProcessBuilder pb = new ProcessBuilder("git", "-C", repo.toString(), "log", "--name-only", "--format=%x01%H%x02%s%x02%b%x03")
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("GIT_CONFIG_GLOBAL", dir.resolve("no-global").toString());
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        String log;
        try {
            Process p = pb.start();
            log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Assumptions.assumeTrue(p.waitFor() == 0, "git log failed");
        } catch (java.io.IOException e) {
            Assumptions.abort("no git executable");
            return;
        }
        List<Commit> expected = new ArrayList<>();
        for (String entry : log.split("\u0001")) {
            int end = entry.indexOf('\u0003');
            if (end < 0) continue;
            String[] head = entry.substring(0, end).split("\u0002", 3);
            List<String> files = Arrays.stream(entry.substring(end + 1).split("\n")).map(String::strip).filter(s -> !s.isEmpty()).sorted().toList();
            expected.add(new Commit(head[0], head[1], head[1] + " " + head[2], files));
        }
        List<Commit> actual = LinkCandidates.commits(repo).stream()
                .map(c -> new Commit(c.hash(), c.subject(), c.message(), c.files().stream().sorted().toList())).toList();
        assertThat(expected).hasSize(7);
        assertThat(actual).containsExactlyElementsOf(expected);
    }
}
