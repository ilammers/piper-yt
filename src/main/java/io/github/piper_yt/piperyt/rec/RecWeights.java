package io.github.piper_yt.piperyt.rec;

/**
 * Every knob of the homepage, in one place. Scores are sums of these weights times signals in
 * [0, 1], so they compare directly: a favorite's fresh upload scores about
 * {@code sourceFavorite + recency + favoriteChannel}.
 *
 * @param sourceFavorite        base score of a recent upload from a favorite channel
 * @param sourceRelated         base score of a video related to one you watched or a favorite's
 * @param sourceDiscover        base score of an upload from a channel found through related
 *                              videos ("discover")
 * @param multiSourceBonus      added per extra source when several suggest the same video
 * @param recency               weight of the recency signal
 * @param recencyHalfLifeHours  age at which the recency signal has halved
 * @param unknownAgeRecency     recency signal for videos without an upload date
 * @param favoriteChannel       affinity for a favorite channel's videos
 * @param watchCount            affinity weight of how often you watched the channel (history)
 * @param watchCountSaturation  watch count at which that signal reaches 1
 * @param completion            affinity weight of how much of the channel's videos you watched
 * @param diversityPenalty      each earlier pick from the same channel multiplies a video's
 *                              score by (1 - this)
 * @param maxItems              length of the homepage
 * @param favoriteWindowDays    how far back favorites' uploads are candidates
 * @param maxRelatedFetches     videos whose related videos are fetched per refresh
 * @param relatedTtlHours       how long fetched related videos are reused
 * @param maxDiscoverChannels   discover channels whose uploads are fetched per refresh
 * @param discoverMinAppearances how often a channel must show up in related videos to be
 *                              discovered
 * @param discoverTtlHours      how long a discovered channel's uploads are reused
 */
public record RecWeights(double sourceFavorite, double sourceRelated, double sourceDiscover,
                         double multiSourceBonus, double recency, double recencyHalfLifeHours,
                         double unknownAgeRecency, double favoriteChannel, double watchCount,
                         int watchCountSaturation, double completion, double diversityPenalty,
                         int maxItems, int favoriteWindowDays, int maxRelatedFetches,
                         int relatedTtlHours, int maxDiscoverChannels,
                         int discoverMinAppearances, int discoverTtlHours) {

    public static final RecWeights DEFAULT = new RecWeights(
            1.0, 0.55, 0.4,
            0.15, 1.0, 72,
            0.25, 0.6, 0.5,
            20, 0.4, 0.35,
            60, 21, 4,
            48, 2,
            3, 24);
}
