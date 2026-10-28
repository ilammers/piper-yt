package io.github.piper_yt.piperyt.rec;

import io.github.piper_yt.piperyt.db.History;
import io.github.piper_yt.piperyt.db.Video;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ranks homepage candidates, entirely locally: recency decay, plus affinity for the channel
 * (favorite; with history on, also how often and how completely you watch it), with a
 * diversity penalty so one channel doesn't take over. Pure: everything it uses is passed in.
 */
public final class Scorer {
    /** Where a candidate came from. */
    public enum Source { FAVORITE, RELATED, DISCOVER }

    /** A video suggested by a source; {@code seedTitle} is the video it's related to. */
    public record Candidate(Video video, Source source, String seedTitle) {
    }

    /**
     * What the scorer knows about you. With history off, {@code stats} and {@code watched}
     * must be empty and {@code useHistory} false: favorites and recency only.
     */
    public record Signals(Set<String> favoriteChannels, Map<String, History.ChannelStats> stats,
                          boolean useHistory, Set<String> watched, Set<String> hidden,
                          Set<String> blockedChannels, boolean hideShorts) {
    }

    /** A ranked video, with the reason shown on its card and a longer explanation. */
    public record Pick(Video video, double score, String reason, String detail) {
    }

    private Scorer() {
    }

    public static List<Pick> rank(final List<Candidate> candidates, final Signals signals,
                                  final RecWeights w, final long now) {
        // Merge duplicates: one entry per video, remembering every source that suggested it
        final Map<String, List<Candidate>> byUrl = new LinkedHashMap<>();
        for (final Candidate c : candidates) {
            final Video v = c.video();
            if (v == null || v.url() == null || v.url().isBlank() || excluded(v, signals)) {
                continue;
            }
            byUrl.computeIfAbsent(v.url(), k -> new ArrayList<>()).add(c);
        }

        final List<Scored> scored = new ArrayList<>();
        for (final List<Candidate> group : byUrl.values()) {
            scored.add(score(group, signals, w, now));
        }
        scored.sort(Comparator.comparingDouble(Scored::base).reversed());

        // Greedy picks: each earlier pick from a channel lowers that channel's next video
        final List<Pick> picks = new ArrayList<>();
        final Map<String, Integer> perChannel = new HashMap<>();
        final List<Scored> pool = new ArrayList<>(scored);
        while (!pool.isEmpty() && picks.size() < w.maxItems()) {
            Scored best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (final Scored s : pool) {
                final double adjusted = s.base() * Math.pow(1 - w.diversityPenalty(),
                        perChannel.getOrDefault(channelKey(s.video()), 0));
                if (adjusted > bestScore) {
                    bestScore = adjusted;
                    best = s;
                }
            }
            pool.remove(best);
            perChannel.merge(channelKey(best.video()), 1, Integer::sum);
            picks.add(new Pick(best.video(), bestScore, best.reason(),
                    best.detail() + String.format(Locale.ROOT, " · score %.2f", bestScore)));
        }
        return picks;
    }

    private record Scored(Video video, double base, String reason, String detail) {
    }

    private static boolean excluded(final Video v, final Signals s) {
        return s.hidden().contains(v.url())
                || v.uploaderUrl() != null && s.blockedChannels().contains(v.uploaderUrl())
                || s.hideShorts() && v.isShort()
                || s.useHistory() && s.watched().contains(v.url());
    }

    private static Scored score(final List<Candidate> group, final Signals s,
                                final RecWeights w, final long now) {
        Candidate primary = group.get(0);
        for (final Candidate c : group) {
            if (sourceWeight(c.source(), w) > sourceWeight(primary.source(), w)) {
                primary = c;
            }
        }
        // The richest metadata among the duplicates (feed items lack durations, etc.)
        Video video = primary.video();
        for (final Candidate c : group) {
            if (c != primary) {
                // The primary's fields win; the others fill its gaps
                video = c.video().mergedWith(video);
            }
        }
        final long sources = group.stream().map(Candidate::source).distinct().count();
        final double base = sourceWeight(primary.source(), w)
                + w.multiSourceBonus() * (sources - 1);
        final double recency = recency(video.uploadDate(), now, w);
        final double affinity = affinity(video.uploaderUrl(), s, w);
        final double total = base + w.recency() * recency + affinity;

        final String channel = video.uploader().isBlank() ? "this channel" : video.uploader();
        final String reason = switch (primary.source()) {
            case FAVORITE -> "From Favorites";
            case RELATED -> "Related to " + primary.seedTitle();
            case DISCOVER -> "Discover";
        };
        final List<String> why = new ArrayList<>();
        why.add(switch (primary.source()) {
            case FAVORITE -> "New upload from " + channel + ", one of your Favorites";
            case RELATED -> "Shown next to “" + primary.seedTitle() + "” on YouTube";
            case DISCOVER -> channel + " keeps showing up in related videos";
        });
        if (sources > 1) {
            why.add("suggested by " + sources + " sources");
        }
        if (video.uploadDate() != null) {
            why.add(ageText(now - video.uploadDate()));
        }
        if (s.useHistory()) {
            final History.ChannelStats stats = s.stats().get(video.uploaderUrl());
            if (stats != null && stats.watchCount() > 0) {
                why.add("you watched " + stats.watchCount() + " from this channel");
            }
        }
        return new Scored(video, total, reason, String.join(" · ", why));
    }

    static double sourceWeight(final Source source, final RecWeights w) {
        return switch (source) {
            case FAVORITE -> w.sourceFavorite();
            case RELATED -> w.sourceRelated();
            case DISCOVER -> w.sourceDiscover();
        };
    }

    /** 1 for a brand-new video, halving every {@code recencyHalfLifeHours}. */
    static double recency(final Long uploadDate, final long now, final RecWeights w) {
        if (uploadDate == null) {
            return w.unknownAgeRecency();
        }
        final double ageHours = Math.max(0, now - uploadDate) / 3_600_000.0;
        return Math.exp(-Math.log(2) * ageHours / w.recencyHalfLifeHours());
    }

    static double affinity(final String channelUrl, final Signals s, final RecWeights w) {
        if (channelUrl == null) {
            return 0;
        }
        double a = s.favoriteChannels().contains(channelUrl) ? w.favoriteChannel() : 0;
        if (s.useHistory()) {
            final History.ChannelStats stats = s.stats().get(channelUrl);
            if (stats != null) {
                a += w.watchCount() * Math.min(1, Math.log1p(stats.watchCount())
                        / Math.log1p(w.watchCountSaturation()));
                a += w.completion() * Math.clamp(stats.completion(), 0, 1);
            }
        }
        return a;
    }

    private static String channelKey(final Video v) {
        return v.uploaderUrl() != null ? v.uploaderUrl() : v.uploader();
    }

    private static String ageText(final long ms) {
        final long hours = ms / 3_600_000;
        if (hours < 1) {
            return "uploaded within the hour";
        }
        if (hours < 48) {
            return "uploaded " + hours + " h ago";
        }
        return "uploaded " + hours / 24 + " days ago";
    }
}
