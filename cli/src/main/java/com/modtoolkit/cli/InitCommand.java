package com.modtoolkit.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "init", description = "Write toolkit.yaml by asking a few questions. Secrets are named, never stored.")
public class InitCommand implements Callable<Integer> {
    @Option(names = "--out", defaultValue = "toolkit.yaml") Path out;
    @Option(names = "--force", description = "Overwrite an existing file") boolean force;

    @Override
    public Integer call() throws Exception {
        if (Files.exists(out) && !force) {
            System.err.println(out + " exists; pass --force to replace it");
            return 2;
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String client = ask(in, "Client name", "acme");
        String root = ask(in, "Source root (git checkout or PDS dump)", "/data/source");
        String refresh = ask(in, "Refresh method (git, manual)", "git");
        String provider = ask(in, "LLM provider (azure-openai, bedrock, openai, anthropic, openai-compatible, none)", "none");
        StringBuilder y = new StringBuilder("client: ").append(client).append("\nsource:\n  root: ").append(root).append("\n  refresh: ").append(refresh)
                .append("\n  encoding: auto\n  format: auto\n  dialect: ibm-enterprise\n  include: [\"**/*\"]\n  exclude: [\"**/*.lst\"]\n");
        if (!provider.equals("none")) {
            y.append("llm:\n  provider: ").append(provider).append("\n  endpoint: ").append(ask(in, "LLM endpoint URL (blank for the provider's default)", ""))
                    .append("\n  api_key_env: TOOLKIT_LLM_KEY\n  chat_model: ").append(ask(in, "Chat model", "gpt-4.1"))
                    .append("\n  embedding_model: ").append(ask(in, "Embedding model", "text-embedding-3-large"))
                    .append("\n  embedding_dim: ").append(ask(in, "Embedding dimension", "3072"))
                    .append("\n  budget_usd_per_run: ").append(ask(in, "Budget in USD per run", "500"))
                    .append("\n  concurrency: 4\n  redact_pii: false\n  pii_patterns: []\n");
        }
        y.append("mcp:\n  transport: http\n  base_url: ").append(ask(in, "MCP base URL", "http://localhost:8081")).append("\n  auth: token\n");
        y.append("zos:\n  enabled: false\n  method: none\n");
        String pkg = ask(in, "Target Java package root", "com.acme.app");
        String local = ask(in, "Local checkout of the modern repository (blank if greenfield)", "");
        y.append("target:\n  java: 21\n  spring_boot: \"3.4\"\n  angular: 19\n  build: maven\n  database: postgresql\n  auth: oidc\n  package_root: ").append(pkg).append('\n');
        if (!local.isBlank()) y.append("  local_path: ").append(local).append("\n  index_existing: true\n");
        else y.append("  index_existing: false\n");
        y.append("scope:\n  phase1_programs: []\n  phase1_jobs: []\n");
        Files.writeString(out, y.toString());
        System.out.println("wrote " + out + ". Set TOOLKIT_DB_URL and the secrets it names, then run: toolkit doctor");
        return 0;
    }

    private static String ask(BufferedReader in, String q, String dflt) throws Exception {
        System.out.print(q + (dflt.isEmpty() ? ": " : " [" + dflt + "]: "));
        System.out.flush();
        String a = in.readLine();
        return a == null || a.isBlank() ? dflt : a.strip();
    }
}
