package io.github.piper_yt.piperyt.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.stream.Description;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Format {
    private Format() {
    }

    public static String duration(final long seconds) {
        if (seconds <= 0) {
            return "";
        }
        final long h = seconds / 3600;
        final long m = (seconds % 3600) / 60;
        final long s = seconds % 60;
        return h > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    /** 1234567 → "1.2M". */
    public static String count(final long n) {
        if (n < 0) {
            return "";
        }
        if (n < 1_000) {
            return Long.toString(n);
        }
        if (n < 1_000_000) {
            return compact(n / 1_000.0) + "K";
        }
        if (n < 1_000_000_000) {
            return compact(n / 1_000_000.0) + "M";
        }
        return compact(n / 1_000_000_000.0) + "B";
    }

    private static String compact(final double v) {
        return v >= 100 ? String.format(Locale.ROOT, "%.0f", v)
                : String.format(Locale.ROOT, "%.1f", v).replace(".0", "");
    }

    /**
     * "3 days ago". The YouTube service requests Zulu text (so titles aren't auto-translated),
     * so dates must be formatted from the parsed {@link DateWrapper}, not the textual date.
     */
    public static String relativeDate(final DateWrapper date) {
        if (date == null) {
            return "";
        }
        final long seconds = Duration.between(date.offsetDateTime(), OffsetDateTime.now())
                .getSeconds();
        if (seconds < 60) {
            return "just now";
        }
        final long[] limits = {3600, 86400, 604800, 2629800, 31557600, Long.MAX_VALUE};
        final long[] units = {60, 3600, 86400, 604800, 2629800, 31557600};
        final String[] names = {"minute", "hour", "day", "week", "month", "year"};
        for (int i = 0; i < names.length; i++) {
            if (seconds < limits[i]) {
                final long n = seconds / units[i];
                return n + " " + names[i] + (n == 1 ? "" : "s") + " ago";
            }
        }
        return "";
    }

    /** "Apr 29, 2026". */
    public static String absoluteDate(final DateWrapper date) {
        if (date == null) {
            return "";
        }
        return date.offsetDateTime().toLocalDate()
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM));
    }

    /** Joins the non-empty parts with " · ". */
    public static String dot(final String... parts) {
        final StringBuilder sb = new StringBuilder();
        for (final String p : parts) {
            if (p == null || p.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    public static String escape(final String text) {
        if (text == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(text.length());
        for (final char c : text.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"]+[^\\s<>\".,;:!?)\\]']");

    /** Escapes plain text for Pango markup, turning bare URLs into links. */
    public static String linkify(final String text) {
        if (text == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        final Matcher m = URL.matcher(text);
        int last = 0;
        while (m.find()) {
            sb.append(escape(text.substring(last, m.start())));
            final String url = escape(m.group());
            sb.append("<a href=\"").append(url).append("\">").append(url).append("</a>");
            last = m.end();
        }
        return sb.append(escape(text.substring(last))).toString();
    }

    /** Converts an extractor {@link Description} to Pango markup with clickable links. */
    public static String descriptionMarkup(final Description description) {
        if (description == null || description.getContent() == null) {
            return "";
        }
        if (description.getType() != Description.HTML) {
            return escape(description.getContent());
        }
        final StringBuilder sb = new StringBuilder();
        for (final Node node : Jsoup.parseBodyFragment(description.getContent()).body()
                .childNodes()) {
            appendMarkup(node, sb);
        }
        return sb.toString().strip();
    }

    private static void appendMarkup(final Node node, final StringBuilder sb) {
        if (node instanceof TextNode text) {
            sb.append(escape(text.getWholeText()));
        } else if (node instanceof Element el) {
            switch (el.tagName()) {
                case "br" -> sb.append('\n');
                case "a" -> {
                    final String href = el.absUrl("href").isEmpty()
                            ? el.attr("href") : el.absUrl("href");
                    if (href.startsWith("http")) {
                        sb.append("<a href=\"").append(escape(href)).append("\">")
                                .append(escape(el.text())).append("</a>");
                    } else {
                        sb.append(escape(el.text()));
                    }
                }
                case "b", "strong" -> wrap(el, sb, "b");
                case "i", "em" -> wrap(el, sb, "i");
                default -> {
                    for (final Node child : el.childNodes()) {
                        appendMarkup(child, sb);
                    }
                    if (el.isBlock()) {
                        sb.append('\n');
                    }
                }
            }
        }
    }

    private static void wrap(final Element el, final StringBuilder sb, final String tag) {
        sb.append('<').append(tag).append('>');
        for (final Node child : el.childNodes()) {
            appendMarkup(child, sb);
        }
        sb.append("</").append(tag).append('>');
    }
}
