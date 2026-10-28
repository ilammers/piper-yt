package io.github.piper_yt.piperyt.net;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.StreamingService.LinkType;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.filter.Filter;
import org.schabi.newpipe.extractor.search.filter.FilterGroup;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.services.youtube.search.filter.YoutubeFilters;
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockApiSettings;
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockExtractorHelper;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Extractor setup and small helpers around its API. */
public final class Extractor {
    private Extractor() {
    }

    /**
     * Whether an item can be watched without an account. Members-only videos can't, so they
     * aren't shown anywhere. (Premium-only videos the extractor already drops as ads, and
     * paid movies come in a format it doesn't list.)
     */
    public static boolean isWatchable(final InfoItem item) {
        return !(item instanceof StreamInfoItem stream && stream.requiresMembership());
    }

    public static void init() {
        final Locale locale = Locale.getDefault();
        final Localization localization = Localization.fromLocale(locale);
        final ContentCountry country = locale.getCountry().isEmpty()
                ? ContentCountry.DEFAULT : new ContentCountry(locale.getCountry());
        // NewPipe.init turns off certificate checks for HttpsURLConnection JVM-wide. Nothing
        // here uses HttpsURLConnection, but put the checks back so nothing ever could.
        final HostnameVerifier verifier = HttpsURLConnection.getDefaultHostnameVerifier();
        final SSLSocketFactory sockets = HttpsURLConnection.getDefaultSSLSocketFactory();
        NewPipe.init(OkHttpDownloader.getInstance(), localization, country);
        HttpsURLConnection.setDefaultHostnameVerifier(verifier);
        HttpsURLConnection.setDefaultSSLSocketFactory(sockets);
        // No PO token provider: the default visionos player client doesn't need one. A
        // bgutil-style provider would plug in with NewPipe.setYoutubePoTokenResolver.

        applySponsorBlockCategories(List.of("sponsor", "selfpromo", "interaction"));
    }

    /**
     * Which SponsorBlock categories playback skips (SponsorBlock's API names, e.g.
     * "selfpromo"); none turns segment fetching off. Applies to videos opened afterwards.
     */
    public static void applySponsorBlockCategories(final List<String> categories) {
        if (categories.isEmpty()) {
            youtube().setSponsorBlockApiSettings(null);
            return;
        }
        // With settings set, StreamInfo.getInfo also fetches SponsorBlock segments
        final SponsorBlockApiSettings sponsorBlock = new SponsorBlockApiSettings();
        sponsorBlock.apiUrl = SponsorBlockExtractorHelper.getApiUrl(youtube().getServiceId());
        sponsorBlock.includeSponsorCategory = categories.contains("sponsor");
        sponsorBlock.includeSelfPromoCategory = categories.contains("selfpromo");
        sponsorBlock.includeInteractionCategory = categories.contains("interaction");
        sponsorBlock.includeIntroCategory = categories.contains("intro");
        sponsorBlock.includeOutroCategory = categories.contains("outro");
        sponsorBlock.includePreviewCategory = categories.contains("preview");
        sponsorBlock.includeFillerCategory = categories.contains("filler");
        sponsorBlock.includeMusicCategory = categories.contains("music_offtopic");
        youtube().setSponsorBlockApiSettings(sponsorBlock);
    }

    public static StreamingService youtube() {
        return ServiceList.YouTube;
    }

    /** What a search looks for; {@link #filterName} is the extractor's content filter. */
    public enum SearchType {
        VIDEOS("Videos", YoutubeFilters.VIDEOS),
        CHANNELS("Channels", YoutubeFilters.CHANNELS),
        PLAYLISTS("Playlists", YoutubeFilters.PLAYLISTS);

        public final String label;
        final String filterName;

        SearchType(final String label, final String filterName) {
            this.label = label;
            this.filterName = filterName;
        }
    }

    /**
     * Result order. The first value of each of these enums is YouTube's default. YouTube
     * now ignores the rating and upload-date sorts (results come back in some other order),
     * so they aren't offered.
     */
    public enum SortBy {
        RELEVANCE("Relevance", "sort_relevance"),
        VIEWS("View count", "sort_view");

        public final String label;
        final String filterName;

        SortBy(final String label, final String filterName) {
            this.label = label;
            this.filterName = filterName;
        }
    }

    /** Upload date limit; videos only. */
    public enum UploadDate {
        ANY("Any time", null),
        HOUR("Last hour", "past_hour"),
        DAY("Today", "past_day"),
        WEEK("This week", "past_week"),
        MONTH("This month", "past_month"),
        YEAR("This year", "past_year");

        public final String label;
        final String filterName;

        UploadDate(final String label, final String filterName) {
            this.label = label;
            this.filterName = filterName;
        }
    }

    /** Video length; videos only. YouTube's cutoffs are 4 and 20 minutes. */
    public enum Length {
        ANY("Any length", null),
        SHORT("Under 4 minutes", "short_video"),
        LONG("Over 20 minutes", "long_video");

        public final String label;
        final String filterName;

        Length(final String label, final String filterName) {
            this.label = label;
            this.filterName = filterName;
        }
    }

    /** Search filters; date and length only apply to video searches. */
    public record SearchFilters(SortBy sort, UploadDate date, Length length) {
        public static final SearchFilters DEFAULT =
                new SearchFilters(SortBy.RELEVANCE, UploadDate.ANY, Length.ANY);

        public boolean isDefault() {
            return equals(DEFAULT);
        }
    }

    /**
     * YouTube search requires an explicit content filter; the no-filter
     * {@code fromQuery(String)} overload throws. The sort filter must be non-null too, or the
     * content type is silently left out of the request and results come back mixed.
     */
    public static SearchQueryHandler search(final String query, final SearchType type,
                                            final SearchFilters filters)
            throws ParsingException {
        final var factory = youtube().getSearchQHFactory();
        final FilterItem content = find(factory.getAvailableContentFilter(), null,
                type.filterName);
        if (content == null) {
            throw new ParsingException("YouTube " + type.filterName + " search filter not found");
        }
        final List<FilterItem> sort = new ArrayList<>();
        if (filters.sort() != SortBy.RELEVANCE) {
            addSort(sort, factory.getAvailableSortFilter(), "sortby", filters.sort().filterName);
        }
        if (type == SearchType.VIDEOS) {
            addSort(sort, factory.getAvailableSortFilter(), "upload_date",
                    filters.date().filterName);
            addSort(sort, factory.getAvailableSortFilter(), "duration",
                    filters.length().filterName);
        }
        return factory.fromQuery(query, List.of(content), sort);
    }

    private static void addSort(final List<FilterItem> out, final Filter available,
                                final String group, final String name)
            throws ParsingException {
        if (name == null) {
            return;
        }
        final FilterItem item = find(available, group, name);
        if (item == null) {
            throw new ParsingException("YouTube " + name + " search filter not found");
        }
        out.add(item);
    }

    /** The item called {@code name}, in group {@code group} (null: any group). */
    private static FilterItem find(final Filter filter, final String group, final String name) {
        for (final FilterGroup g : filter.getFilterGroups()) {
            if (group != null && !group.equals(g.groupName)) {
                continue;
            }
            for (final FilterItem item : g.filterItems) {
                if (name.equals(item.getName())) {
                    return item;
                }
            }
        }
        return null;
    }

    /** The kind of YouTube page {@code url} points to, or {@link LinkType#NONE}. */
    public static LinkType linkType(final String url) {
        try {
            return youtube().getLinkTypeByUrl(url);
        } catch (final Exception e) {
            return LinkType.NONE;
        }
    }
}
