package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.net.Extractor;
import io.github.piper_yt.piperyt.util.Format;
import org.gnome.adw.Avatar;
import org.gnome.adw.HeaderBar;
import org.gnome.adw.NavigationPage;
import org.gnome.adw.Spinner;
import org.gnome.adw.StatusPage;
import org.gnome.adw.ToolbarView;
import org.gnome.adw.ViewStack;
import org.gnome.adw.ViewSwitcher;
import org.gnome.adw.ViewSwitcherPolicy;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.ContentFit;
import org.gnome.gtk.Expander;
import org.gnome.gtk.Image;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Overflow;
import org.gnome.gtk.Picture;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.PositionType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.Stack;
import org.gnome.gtk.Widget;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelTabInfo;
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;

import java.util.ArrayList;
import java.util.List;

/** A channel: banner, avatar and description above tabs of videos, shorts, streams, playlists. */
final class ChannelPage {
    private static final int BANNER_HEIGHT = 150;

    private final MainWindow main;
    private final String url;
    private final NavigationPage page;
    private final Stack stack = new Stack();
    private final StatusPage errorStatus = new StatusPage();
    private final ScrolledWindow scroller = new ScrolledWindow();
    private final ViewStack tabStack = new ViewStack();
    private final List<Tab> tabs = new ArrayList<>();

    private ChannelInfo info;

    /** One tab's grid, filled the first time the tab is shown. */
    private final class Tab {
        final ListLinkHandler handler;
        final ItemGrid grid;
        final Stack content = new Stack();
        final StatusPage status = new StatusPage();
        boolean started;

        Tab(final ListLinkHandler handler) {
            this.handler = handler;
            grid = new ItemGrid(main, VideoCard.Layout.GRID,
                    ChannelTabs.CHANNELS.equals(name(handler)));
            final Spinner spinner = new Spinner();
            spinner.setSizeRequest(32, 32);
            spinner.setMarginTop(48);
            spinner.setHalign(Align.CENTER);
            status.addCssClass("compact");
            content.setVhomogeneous(false);
            content.addNamed(spinner, "loading");
            content.addNamed(status, "status");
            content.addNamed(grid.widget(), "items");
        }

        void start() {
            if (started) {
                return;
            }
            started = true;
            Ui.fetch(() -> ChannelTabInfo.getInfo(Extractor.youtube(), handler),
                    tab -> show(tab.getRelatedItems(), tab.getNextPage()),
                    error -> {
                        started = false;
                        status.setIconName("dialog-error-symbolic");
                        status.setTitle("Couldn't load " + title(name(handler)).toLowerCase());
                        status.setDescription(Ui.errorMessage(error));
                        content.setVisibleChildName("status");
                    });
        }

        void show(final List<? extends InfoItem> items, final Page next) {
            started = true;
            grid.reset(items, next, name(handler).equals(ChannelTabs.VIDEOS)
                    ? p -> ChannelInfo.getMoreItems(Extractor.youtube(), info.getUrl(), p)
                    : p -> ChannelTabInfo.getMoreItems(Extractor.youtube(), handler, p));
            if (grid.isEmpty()) {
                status.setIconName("folder-open-symbolic");
                status.setTitle("Nothing here yet");
                status.setDescription(null);
                content.setVisibleChildName("status");
            } else {
                content.setVisibleChildName("items");
            }
        }
    }

    ChannelPage(final MainWindow main, final String url, final String name) {
        this.main = main;
        this.url = url;

        final Button browser = Button.fromIconName("web-browser-symbolic");
        browser.setTooltipText("Open in browser");
        browser.onClicked(() -> main.openInBrowser(info != null ? info.getUrl() : url));
        final HeaderBar header = new HeaderBar();
        header.packEnd(browser);

        final Spinner spinner = new Spinner();
        spinner.setSizeRequest(48, 48);
        spinner.setHalign(Align.CENTER);
        spinner.setValign(Align.CENTER);

        errorStatus.setIconName("dialog-error-symbolic");
        errorStatus.setTitle("Couldn't load channel");
        final Button retry = Button.withLabel("Retry");
        retry.addCssClass("pill");
        retry.setHalign(Align.CENTER);
        retry.onClicked(this::load);
        errorStatus.setChild(retry);

        scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC);
        scroller.onEdgeReached(pos -> {
            final Tab tab = visibleTab();
            if (pos == PositionType.BOTTOM && tab != null) {
                tab.grid.loadMore();
            }
        });

        stack.addNamed(spinner, "loading");
        stack.addNamed(errorStatus, "error");
        stack.addNamed(scroller, "content");

        final ToolbarView toolbar = new ToolbarView();
        toolbar.addTopBar(header);
        toolbar.setContent(stack);
        page = new NavigationPage(toolbar, name == null ? "Channel" : name);
        load();
    }

    NavigationPage page() {
        return page;
    }

    private void load() {
        stack.setVisibleChildName("loading");
        Ui.fetch(() -> ChannelInfo.getInfo(Extractor.youtube(), url),
                this::show,
                error -> {
                    errorStatus.setDescription(Ui.errorMessage(error));
                    stack.setVisibleChildName("error");
                });
    }

    private void show(final ChannelInfo loaded) {
        info = loaded;
        page.setTitle(info.getName());

        final Box box = new Box(Orientation.VERTICAL, 18);
        box.setMarginTop(12);
        box.setMarginBottom(12);
        if (info.getBannerUrl() != null && !info.getBannerUrl().isBlank()) {
            box.append(buildBanner());
        }
        box.append(buildHeading());
        if (info.getDescription() != null && !info.getDescription().isBlank()) {
            box.append(buildDescription());
        }
        box.append(buildTabs());

        scroller.setChild(box);
        stack.setVisibleChildName("content");
    }

    private Widget buildBanner() {
        final Picture banner = new Picture();
        banner.setContentFit(ContentFit.COVER);
        banner.setCanShrink(true);
        banner.setOverflow(Overflow.HIDDEN);
        banner.addCssClass("channel-banner");
        banner.setSizeRequest(-1, BANNER_HEIGHT);
        banner.setMarginStart(18);
        banner.setMarginEnd(18);
        Images.load(info.getBannerUrl(), banner, banner::setPaintable);
        return banner;
    }

    private Box buildHeading() {
        final Box box = new Box(Orientation.HORIZONTAL, 18);
        box.setMarginStart(24);
        box.setMarginEnd(24);

        final Avatar avatar = new Avatar(88, info.getName(), true);
        Images.load(ChannelCard.largerAvatar(info.getAvatarUrl()), avatar, avatar::setCustomImage);
        box.append(avatar);

        final Box text = new Box(Orientation.VERTICAL, 4);
        text.setValign(Align.CENTER);
        final Box nameRow = new Box(Orientation.HORIZONTAL, 8);
        final Label name = Ui.wrapping(info.getName(), 1, "title-1");
        name.setSelectable(true);
        nameRow.append(name);
        if (info.isVerified()) {
            final Image verified = Image.fromIconName("object-select-symbolic");
            verified.setTooltipText("Verified");
            verified.addCssClass("dim-label");
            nameRow.append(verified);
        }
        text.append(nameRow);
        if (info.getSubscriberCount() >= 0) {
            text.append(Ui.label(Format.count(info.getSubscriberCount()) + " subscribers",
                    "dim-label"));
        }
        text.setHexpand(true);
        box.append(text);
        final Widget favorite = FavoriteButton.create(main, info.getUrl(), info.getName(),
                info.getAvatarUrl(), info.getSubscriberCount(), info.getDescription());
        favorite.setValign(Align.CENTER);
        box.append(favorite);
        return box;
    }

    private Expander buildDescription() {
        final Label label = Ui.wrapping("", 0);
        label.setMarkup(Format.linkify(info.getDescription().strip()));
        label.setSelectable(true);
        label.setMarginTop(12);
        main.handleLinks(label);

        final Expander expander = new Expander("About");
        expander.setChild(label);
        expander.setMarginStart(24);
        expander.setMarginEnd(24);
        return expander;
    }

    private Box buildTabs() {
        tabStack.setVhomogeneous(false);
        for (final ListLinkHandler handler : info.getTabs()) {
            final String tabName = name(handler);
            final String title = title(tabName);
            if (title == null) {
                continue;
            }
            final Tab tab = new Tab(handler);
            tabs.add(tab);
            tabStack.addTitled(tab.content, tabName, title).setIconName(icon(tabName));
            if (tabName.equals(ChannelTabs.VIDEOS)) {
                // Channel info already carries the first page of videos
                tab.show(info.getRelatedItems(), info.getNextPage());
            }
        }
        final Box box = new Box(Orientation.VERTICAL, 6);
        if (tabs.isEmpty()) {
            final StatusPage empty = new StatusPage();
            empty.setIconName("folder-open-symbolic");
            empty.setTitle("This channel has no videos or playlists");
            empty.addCssClass("compact");
            box.append(empty);
            return box;
        }

        final ViewSwitcher switcher = new ViewSwitcher();
        switcher.setStack(tabStack);
        switcher.setPolicy(ViewSwitcherPolicy.WIDE);
        switcher.setHalign(Align.CENTER);
        box.append(switcher);
        box.append(tabStack);
        tabStack.onNotify("visible-child", pspec -> startVisibleTab());
        startVisibleTab();
        return box;
    }

    private void startVisibleTab() {
        final Tab tab = visibleTab();
        if (tab != null) {
            tab.start();
        }
    }

    private Tab visibleTab() {
        final String visible = tabStack.getVisibleChildName();
        for (final Tab tab : tabs) {
            if (name(tab.handler).equals(visible)) {
                return tab;
            }
        }
        return null;
    }

    private static String name(final ListLinkHandler handler) {
        return handler.getContentFilters().get(0).getName();
    }

    /** The tab's label, or null for tabs the app doesn't show. */
    private static String title(final String tab) {
        return switch (tab) {
            case ChannelTabs.VIDEOS -> "Videos";
            case ChannelTabs.SHORTS -> "Shorts";
            case ChannelTabs.LIVESTREAMS -> "Live";
            case ChannelTabs.PODCASTS -> "Podcasts";
            case ChannelTabs.PLAYLISTS -> "Playlists";
            case ChannelTabs.CHANNELS -> "Channels";
            default -> null;
        };
    }

    private static String icon(final String tab) {
        return switch (tab) {
            case ChannelTabs.SHORTS -> "phone-symbolic";
            case ChannelTabs.LIVESTREAMS -> "media-record-symbolic";
            case ChannelTabs.PODCASTS -> "audio-x-generic-symbolic";
            case ChannelTabs.PLAYLISTS -> "view-list-symbolic";
            case ChannelTabs.CHANNELS -> "system-users-symbolic";
            default -> "video-x-generic-symbolic";
        };
    }
}
