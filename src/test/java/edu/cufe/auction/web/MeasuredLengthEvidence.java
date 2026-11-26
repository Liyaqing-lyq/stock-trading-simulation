package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.LiveCompletionInbox;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/** 新工程档的逐段来源封存。每段固定原文件、提供方回执及父段封存；补写账本原因不构成外部来源。 */
final class MeasuredLengthEvidence {
    static final String FILE = "measured-length-evidence.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Seal(String policy, int httpCalls, String archiveSha256, String parentSealSha256) { }
    private MeasuredLengthEvidence() { }

    /** 世界及外发文件全部关闭后封存。没有完整原始回执的段不能成为续段的来源。 */
    static void seal(Path folder, Path parent) throws IOException {
        var result = JSON.readTree(folder.resolve("result.json").toFile());
        if (!result.at("/providerProvenance/evidenceComplete").asBoolean()) return;
        int calls = result.path("httpCalls").intValue();
        var stamp = calculate(folder, parent, calls);
        Files.write(folder.resolve(FILE), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(stamp), StandardOpenOption.CREATE_NEW);
    }

    /** 从本段逐个走回原始开局，父段 journal 必须是子段的真实字节前缀。 */
    static java.util.Set<LiveCompletionInbox.AttemptKey> restore(Path initial, MeasuredLengthPolicy policy, DecisionRun ledger) throws IOException {
        var visited = new HashSet<Path>();
        var attempts = new HashSet<LiveCompletionInbox.AttemptKey>();
        var decisions = ledger.state().decisions().stream().collect(java.util.stream.Collectors.toMap(
                edu.cufe.auction.world.DecisionModel.Decision::actionId, java.util.function.Function.identity()));
        Path child = null;
        String source = null;
        String mode = null;
        for (Path path = initial.toRealPath(); path != null; ) {
            if (!visited.add(path)) throw invalid();
            var result = JSON.readTree(path.resolve("result.json").toFile());
            var config = JSON.readTree(path.resolve("config.json").toFile());
            var cfg = JSON.treeToValue(config, AutonomousLifecycleProbe.Config.class);
            if (!config.equals(result.path("config")) || !AutonomousLifecycleProbe.usesMeasuredLength(cfg.mode())
                    || !result.at("/live/physicalInFlight").isIntegralNumber()
                    || result.at("/live/physicalInFlight").longValue() != 0
                    || !result.at("/providerProvenance/evidenceComplete").asBoolean()) throw invalid();
            if (source == null) {
                source = cfg.sourceCommit();
                mode = cfg.mode();
            }
            if (!source.equals(cfg.sourceCommit()) || !mode.equals(cfg.mode())) throw invalid();
            Path parent = cfg.seed() == null ? null : Path.of(cfg.seed()).toRealPath();
            int calls = result.path("httpCalls").intValue();
            var observed = JSON.readValue(path.resolve(FILE).toFile(), Seal.class);
            if (!calculate(path, parent, calls).equals(observed)) throw invalid();
            if (child != null) {
                Path prior = path.resolve("world.journal"), later = child.resolve("world.journal");
                long size = Files.size(prior), difference = Files.mismatch(prior, later);
                if (size > Files.size(later) || (difference != -1 && difference != size)) throw invalid();
                for (String name : List.of("system-prompt.txt", SceneRoutes.FILE_NAME, ProbeProviderEvidence.POLICY_FILE))
                    if (Files.mismatch(path.resolve(name), child.resolve(name)) != -1) throw invalid();
            }
            ProbeProviderEvidence.validateSeed(path, result, true, true);
            for (int index = 1; index <= calls; index++) {
                var call = JSON.readValue(path.resolve("call-" + index + ".json").toFile(), DecisionRun.Call.class);
                if (!attempts.add(LiveCompletionInbox.AttemptKey.of(call))) throw invalid();
                var receipt = JSON.readValue(path.resolve("reply-" + index + ".json").toFile(), LiveCompletionInbox.Completion.class);
                var d = decisions.get(call.actionId());
                if (d == null) throw invalid();
                if (!call.runId().equals(d.runId()) || !call.plan().equals(d.plan()) || !call.inputText().equals(d.inputText())
                        || !call.allowedTools().equals(d.allowedTools())) throw invalid();
                var a = d.attempts().stream().filter(x -> x.attemptId().equals(call.attemptId())).findFirst().orElseThrow(MeasuredLengthEvidence::invalid);
                if (a.attemptNo() != call.attemptNo() || a.recoveryGeneration() != call.recoveryGeneration()
                        || !a.replyReceived() || !a.settledDone() || !a.replyBody().equals(receipt.reply().body())
                        || !a.usageSource().equals(receipt.reply().usageSource())
                        || !java.util.Objects.equals(a.declaredUsage(), receipt.reply().usage())) throw invalid();
                if (cfg.runUntilTarget() && !receipt.businessReply()
                        && a.outcome() != edu.cufe.auction.world.DecisionModel.Outcome.TECHNICAL_FAILED) throw invalid();
                if ("PROVIDER_LENGTH".equals(receipt.failureCode()) && !policy.remember(call, receipt.reply())) throw invalid();
            }
            child = path;
            path = parent;
        }
        return attempts;
    }

    private static Seal calculate(Path folder, Path parent, int calls) throws IOException {
        var config = JSON.readValue(folder.resolve("config.json").toFile(), AutonomousLifecycleProbe.Config.class);
        if (calls < 0 || calls > config.callLimit()) throw invalid();
        var digest = sha256();
        for (String name : List.of("config.json", "result.json", "system-prompt.txt", SceneRoutes.FILE_NAME,
                ProbeProviderEvidence.POLICY_FILE, "world.journal")) add(folder, name, digest);
        if (config.runUntilTarget()) add(folder,AutonomousLifecycleProbe.INPUT_EVENTS,digest);
        for (int index = 1; index <= calls; index++)
            for (String prefix : List.of("call-", "reply-", "provider-")) add(folder, prefix + index + ".json", digest);
        String parentHash = parent == null ? null : HexFormat.of().formatHex(fileDigest(parent.resolve(FILE)));
        return new Seal(MeasuredLengthPolicy.VERSION, calls, HexFormat.of().formatHex(digest.digest()), parentHash);
    }

    private static void add(Path dir, String name, MessageDigest digest) throws IOException {
        digest.update(name.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(fileDigest(dir.resolve(name)));
    }

    private static byte[] fileDigest(Path file) throws IOException {
        var h = sha256();
        try (var stream = new DigestInputStream(Files.newInputStream(file), h)) {
            stream.transferTo(OutputStream.nullOutputStream());
        }
        return h.digest();
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("工程截断策略缺少一致的父段、原始回执或完整账本来源");
    }
}
