package org.xcore.plugin.ui.menu;

import mindustry.gen.Iconc;
import mindustry.ui.builder.MenuResult;
import org.xcore.plugin.gamemode.hexed.HexedRanks.HexedRank;
import org.xcore.plugin.integration.profile.ProfileSection;
import org.xcore.plugin.integration.profile.ProfileSectionView;
import org.xcore.plugin.localization.Localization;
import org.xcore.plugin.model.AggregatedPlayerStats;
import org.xcore.plugin.model.ModeStatsSummary;
import org.xcore.plugin.model.PlayerData;
import org.xcore.plugin.model.PlayerStatsOverview;
import org.xcore.plugin.permission.PermissionNodes;
import org.xcore.plugin.player.Badge;
import org.xcore.plugin.rating.RatingLeague;
import org.xcore.plugin.service.PlayerDisplayService;
import org.xcore.plugin.session.Session;
import org.xcore.plugin.session.SessionService;
import org.xcore.plugin.ui.kit.Accent;
import org.xcore.plugin.ui.kit.Kit;
import org.xcore.plugin.ui.kit.Screen;
import org.xcore.plugin.ui.kit.TextWidth;
import org.xcore.ui.Ui;
import org.xcore.ui.VNode;
import org.xcore.ui.runtime.ActionRouter;
import org.xcore.ui.runtime.ControllerContext;
import org.xcore.ui.runtime.UiController;
import org.xcore.ui.runtime.UpdateResult;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static com.ospx.flubundle.Bundle.args;
import static org.xcore.plugin.ui.kit.Kit.GAP;
import static org.xcore.plugin.ui.kit.Texts.locale;
import static org.xcore.plugin.ui.kit.Texts.t;

/**
 * A player's profile ({@code /stats}) and the list of who is online ({@code /players}) as tabs of
 * one dialog: who the player is, what the modes say about them, their figures, and the players
 * on the server as rows that open their profiles. Laid out once per {@link Screen}.
 */
public class PlayerProfileUiController implements UiController<PlayerProfileUiController.ProfileModel, PlayerProfileUiController.ProfileEvent> {

    public static final int PLAYERS_PER_PAGE = 8;

    /** The key of the details load: inspecting another player supersedes the load of the one before. */
    public static final String DETAILS = "details";

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    public enum Tab {
        OVERVIEW,
        STATS,
        PLAYERS
    }

    public enum AdminFilter {
        ALL,
        ADMINS_ONLY,
        NON_ADMINS;

        public AdminFilter next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public record OnlinePlayerRow(
            String uuid,
            int pid,
            String username,
            String nickname,
            String customNickname,
            String activeBadge,
            String badgeSymbolColorMode,
            boolean isAdmin,
            String playerColorHex
    ) {}

    public record ProfileModel(
            // Viewer identity
            String viewerUuid,

            // Target Player Identity
            String targetUuid,
            int pid,
            String username,
            String nickname,
            String customNickname,
            String description,
            boolean isTargetAdmin,
            boolean isTargetOnline,
            long createdModelTime,
            int totalPlayTime,
            String activeBadge,
            String badgeSymbolColorMode,
            Set<String> unlockedBadges,
            String playerColorHex,

            // Gamemode Progression & Stats
            List<ProfileSectionView> sections,
            int hexedPoints,
            HexedRank hexedRank,
            Integer hexedTopRank,
            PlayerStatsOverview stats,
            boolean isStatsLoading,

            // Viewer & Context
            boolean isSelf,
            boolean isViewerAdmin,
            Tab tab,
            boolean viewingFromPlayersTab,

            // Players Tab State
            AdminFilter adminFilter,
            int playersPage,
            int totalOnlineCount,
            List<OnlinePlayerRow> onlinePlayers,
            String feedbackMessage
    ) {
        public ProfileModel withTab(Tab newTab) {
            return new ProfileModel(viewerUuid, targetUuid, pid, username, nickname, customNickname, description, isTargetAdmin,
                    isTargetOnline, createdModelTime, totalPlayTime, activeBadge, badgeSymbolColorMode,
                    unlockedBadges, playerColorHex, sections, hexedPoints, hexedRank, hexedTopRank,
                    stats, isStatsLoading, isSelf, isViewerAdmin, newTab,
                    viewingFromPlayersTab, adminFilter, playersPage, totalOnlineCount, onlinePlayers, "");
        }

        /** What has been read about the target so far, {@code null} while it is still loading. */
        public ProfileDetails details() {
            return isStatsLoading ? null : new ProfileDetails(stats, hexedTopRank, sections);
        }

        public ProfileModel withDetails(ProfileDetails details) {
            return new ProfileModel(viewerUuid, targetUuid, pid, username, nickname, customNickname, description, isTargetAdmin,
                    isTargetOnline, createdModelTime, totalPlayTime, activeBadge, badgeSymbolColorMode,
                    unlockedBadges, playerColorHex, details.sections(), hexedPoints, hexedRank, details.hexedTopRank(),
                    details.stats(), false, isSelf, isViewerAdmin, tab,
                    viewingFromPlayersTab, adminFilter, playersPage, totalOnlineCount, onlinePlayers, feedbackMessage);
        }

        /** @param details what is already loaded about {@code target}, {@code null} while it is being read */
        public ProfileModel withTarget(PlayerData target, boolean isOnline, boolean isAdmin, String colorHex,
                                      ProfileDetails details, boolean fromPlayersTab) {
            Objects.requireNonNull(target, "target");
            return new ProfileModel(
                    viewerUuid,
                    target.uuid,
                    target.pid,
                    target.username != null ? target.username : "",
                    target.nickname != null ? target.nickname : "",
                    target.customNickname != null ? target.customNickname : "",
                    target.description != null ? target.description : "",
                    isAdmin,
                    isOnline,
                    target.createdModelTime,
                    target.totalPlayTime,
                    target.activeBadge != null ? target.activeBadge : "",
                    target.badgeSymbolColorMode != null ? target.badgeSymbolColorMode : "default",
                    target.unlockedBadges != null ? target.unlockedBadges : Set.of(),
                    colorHex != null ? colorHex : "FFD37F",
                    details != null ? details.sections() : List.of(),
                    target.hexedPoints,
                    target.hexedRank(),
                    details != null ? details.hexedTopRank() : null,
                    details != null ? details.stats() : null,
                    details == null,
                    Objects.equals(viewerUuid, target.uuid),
                    isViewerAdmin,
                    Tab.OVERVIEW,
                    fromPlayersTab,
                    adminFilter,
                    playersPage,
                    totalOnlineCount,
                    onlinePlayers,
                    ""
            );
        }

        public ProfileModel withPlayersPage(int newPage) {
            return new ProfileModel(viewerUuid, targetUuid, pid, username, nickname, customNickname, description, isTargetAdmin,
                    isTargetOnline, createdModelTime, totalPlayTime, activeBadge, badgeSymbolColorMode,
                    unlockedBadges, playerColorHex, sections, hexedPoints, hexedRank, hexedTopRank,
                    stats, isStatsLoading, isSelf, isViewerAdmin, tab,
                    viewingFromPlayersTab, adminFilter, newPage, totalOnlineCount, onlinePlayers, feedbackMessage);
        }

        public ProfileModel withAdminFilter(AdminFilter filter, List<OnlinePlayerRow> players) {
            return new ProfileModel(viewerUuid, targetUuid, pid, username, nickname, customNickname, description, isTargetAdmin,
                    isTargetOnline, createdModelTime, totalPlayTime, activeBadge, badgeSymbolColorMode,
                    unlockedBadges, playerColorHex, sections, hexedPoints, hexedRank, hexedTopRank,
                    stats, isStatsLoading, isSelf, isViewerAdmin, tab,
                    viewingFromPlayersTab, filter, 1, totalOnlineCount, players, feedbackMessage);
        }

        public ProfileModel withRefreshedPlayers(List<OnlinePlayerRow> players, int totalCount) {
            return new ProfileModel(viewerUuid, targetUuid, pid, username, nickname, customNickname, description, isTargetAdmin,
                    isTargetOnline, createdModelTime, totalPlayTime, activeBadge, badgeSymbolColorMode,
                    unlockedBadges, playerColorHex, sections, hexedPoints, hexedRank, hexedTopRank,
                    stats, isStatsLoading, isSelf, isViewerAdmin, tab,
                    viewingFromPlayersTab, adminFilter, 1, totalCount, players, feedbackMessage);
        }

        public ProfileModel withRefreshedPlayersPreservingPage(List<OnlinePlayerRow> players, int totalCount) {
            int maxPage = Math.max(1, (int) Math.ceil((double) players.size() / PLAYERS_PER_PAGE));
            int page = Math.clamp(playersPage, 1, maxPage);
            return new ProfileModel(viewerUuid, targetUuid, pid, username, nickname, customNickname, description, isTargetAdmin,
                    isTargetOnline, createdModelTime, totalPlayTime, activeBadge, badgeSymbolColorMode,
                    unlockedBadges, playerColorHex, sections, hexedPoints, hexedRank, hexedTopRank,
                    stats, isStatsLoading, isSelf, isViewerAdmin, tab,
                    viewingFromPlayersTab, adminFilter, page, totalCount, players, feedbackMessage);
        }

        public ProfileModel withFeedback(String msg) {
            return new ProfileModel(viewerUuid, targetUuid, pid, username, nickname, customNickname, description, isTargetAdmin,
                    isTargetOnline, createdModelTime, totalPlayTime, activeBadge, badgeSymbolColorMode,
                    unlockedBadges, playerColorHex, sections, hexedPoints, hexedRank, hexedTopRank,
                    stats, isStatsLoading, isSelf, isViewerAdmin, tab,
                    viewingFromPlayersTab, adminFilter, playersPage, totalOnlineCount, onlinePlayers, msg);
        }
    }

    public sealed interface ProfileEvent {
        record SelectTab(Tab tab) implements ProfileEvent {}
        record DetailsLoaded(String targetUuid, ProfileDetails details) implements ProfileEvent {}
        record InspectPlayer(String targetUuid) implements ProfileEvent {}
        record BackToPlayers() implements ProfileEvent {}
        record Back() implements ProfileEvent {}
        record CycleAdminFilter() implements ProfileEvent {}
        record ChangePlayersPage(int page) implements ProfileEvent {}
        record RefreshPlayers() implements ProfileEvent {}
        record OpenSettings() implements ProfileEvent {}
        record OpenAuditHistory() implements ProfileEvent {}
        record OpenAuditActions() implements ProfileEvent {}
        /** The viewer's own match history; offered on their own profile only. */
        record OpenMatches() implements ProfileEvent {}
        record Close() implements ProfileEvent {}
    }

    private static final String SEPARATOR = "  [darkgray]|[]  ";

    private final PlayerMenu playerMenu;
    private final AuditHistoryMenu auditHistoryMenu;
    private final SessionService sessionService;
    private final PlayerDisplayService playerDisplayService;
    private final Session session;
    private final PlayerData initialTargetData;

    public PlayerProfileUiController(PlayerMenu playerMenu,
                                     AuditHistoryMenu auditHistoryMenu,
                                     SessionService sessionService,
                                     PlayerDisplayService playerDisplayService,
                                     Session session,
                                     PlayerData targetData) {
        this.playerMenu = playerMenu;
        this.auditHistoryMenu = auditHistoryMenu;
        this.sessionService = sessionService;
        this.playerDisplayService = playerDisplayService;
        this.session = session;
        this.initialTargetData = targetData;
    }

    /** @param details what is already loaded about the target, {@code null} while it is being read */
    public ProfileModel createInitialModel(Tab tab, ProfileDetails details) {
        return createModel(session, initialTargetData, tab, details, sessionService, playerDisplayService);
    }

    public static ProfileModel createModel(Session session,
                                           PlayerData targetData,
                                           Tab initialTab,
                                           ProfileDetails details,
                                           SessionService sessionService,
                                           PlayerDisplayService playerDisplayService) {
        Objects.requireNonNull(targetData, "targetData");
        String viewerUuid = session != null && session.data != null ? session.data.uuid : "";
        boolean isSelf = session != null && session.data != null && Objects.equals(session.data.uuid, targetData.uuid);
        // What the profile offers is drawn from this; each action asks again when it is pressed.
        boolean isViewerAdmin = session != null && (session.has(PermissionNodes.PLAYERS_SETTINGS_OTHERS)
                || session.has(PermissionNodes.MODERATION_AUDIT_OTHERS));

        Session targetSession = sessionService != null ? sessionService.get(targetData.uuid) : (isSelf ? session : null);
        boolean isOnline = targetSession != null && targetSession.player != null;

        String playerColorHex = PlayerSettingsUiController.resolvePlayerColorHex(session, targetData);

        List<OnlinePlayerRow> onlinePlayers = resolveOnlinePlayers(session, sessionService, playerDisplayService, AdminFilter.ALL);
        int totalOnline = sessionService != null ? sessionService.getCachedCount() : onlinePlayers.size();

        return new ProfileModel(
                viewerUuid,
                targetData.uuid,
                targetData.pid,
                targetData.username != null ? targetData.username : "",
                targetData.nickname != null ? targetData.nickname : "",
                targetData.customNickname != null ? targetData.customNickname : "",
                targetData.description != null ? targetData.description : "",
                isOnline && targetSession.player.admin,
                isOnline,
                targetData.createdModelTime,
                targetData.totalPlayTime,
                targetData.activeBadge != null ? targetData.activeBadge : "",
                targetData.badgeSymbolColorMode != null ? targetData.badgeSymbolColorMode : "default",
                targetData.unlockedBadges != null ? targetData.unlockedBadges : Set.of(),
                playerColorHex,
                details != null ? details.sections() : List.of(),
                targetData.hexedPoints,
                targetData.hexedRank(),
                details != null ? details.hexedTopRank() : null,
                details != null ? details.stats() : null,
                details == null,
                isSelf,
                isViewerAdmin,
                initialTab != null ? initialTab : Tab.OVERVIEW,
                false,
                AdminFilter.ALL,
                1,
                totalOnline,
                onlinePlayers,
                ""
        );
    }

    public static List<OnlinePlayerRow> resolveOnlinePlayers(Session viewerSession,
                                                            SessionService sessionService,
                                                            PlayerDisplayService playerDisplayService,
                                                            AdminFilter filter) {
        if (sessionService == null) return List.of();
        return sessionService.streamCached()
                .filter(s -> s != null && s.data != null && s.player != null)
                .filter(s -> matchesFilter(s, filter))
                .sorted(Comparator
                        .comparing((Session s) -> playerDisplayService != null
                                ? playerDisplayService.resolveBaseName(s.data, s.player)
                                : (s.data.nickname != null ? s.data.nickname : ""), String.CASE_INSENSITIVE_ORDER)
                        .thenComparingInt(s -> s.data.pid))
                .map(s -> new OnlinePlayerRow(
                        s.data.uuid,
                        s.data.pid,
                        s.data.username != null ? s.data.username : "",
                        s.data.nickname != null ? s.data.nickname : "",
                        s.data.customNickname != null ? s.data.customNickname : "",
                        s.data.activeBadge != null ? s.data.activeBadge : "",
                        s.data.badgeSymbolColorMode != null ? s.data.badgeSymbolColorMode : "default",
                        s.player.admin,
                        PlayerSettingsUiController.resolvePlayerColorHex(s, s.data)
                ))
                .toList();
    }

    private static boolean matchesFilter(Session s, AdminFilter filter) {
        if (filter == null || filter == AdminFilter.ALL) return true;
        boolean isAdmin = s.player.admin;
        return filter == AdminFilter.ADMINS_ONLY ? isAdmin : !isAdmin;
    }

    public static String renderHexedProgressBar(HexedRank rank, int currentPoints, int barWidth, Localization local) {
        if (rank == null || !rank.hasNext()) {
            return t(local, "player-stats-max-rank");
        }
        int required = rank.next.requirements.wins();
        float pct = Math.clamp(required > 0 ? (float) currentPoints / required : 0f, 0f, 1f);
        int filled = Math.round(pct * barWidth);
        int empty = barWidth - filled;

        return "[accent]" + "■".repeat(Math.max(0, filled))
                + "[darkgray]" + "■".repeat(Math.max(0, empty))
                + "[] [white]" + Math.round(pct * 100f) + "%[]";
    }

    public static String renderRatingLeagueProgressBar(RatingLeague league, int currentRating, int barWidth, Localization local) {
        if (league == null || !league.hasNext()) {
            return t(local, "player-stats-max-league");
        }
        RatingLeague next = league.next();
        int min = league.minimumRating();
        int max = next.minimumRating();
        int span = Math.max(1, max - min);
        float pct = Math.clamp((float) (currentRating - min) / span, 0f, 1f);
        int filled = Math.round(pct * barWidth);
        int empty = barWidth - filled;

        return "[sky]" + "■".repeat(Math.max(0, filled))
                + "[darkgray]" + "■".repeat(Math.max(0, empty))
                + "[] [white]" + Math.round(pct * 100f) + "%[]";
    }

    public static String renderBlockRatioBar(long built, long decon, long destroyed, int totalBars) {
        long sum = built + decon + destroyed;
        if (sum <= 0) {
            return "[darkgray]" + "■".repeat(totalBars) + "[]";
        }
        int builtBars = Math.round((float) built / sum * totalBars);
        int deconBars = Math.round((float) decon / sum * totalBars);
        int destroyedBars = totalBars - builtBars - deconBars;
        if (destroyedBars < 0) {
            if (builtBars > deconBars) builtBars += destroyedBars;
            else deconBars += destroyedBars;
            destroyedBars = 0;
        }
        return "[lime]" + "■".repeat(Math.max(0, builtBars))
                + "[orange]" + "■".repeat(Math.max(0, deconBars))
                + "[scarlet]" + "■".repeat(Math.max(0, destroyedBars)) + "[]";
    }

    public static String formatDuration(int totalMinutes, Localization local) {
        if (totalMinutes <= 0) {
            return t(local, "player-menu-time-minutes", args("value", 0), "0m");
        }

        int days = totalMinutes / (60 * 24);
        int hours = (totalMinutes / 60) % 24;
        int minutes = totalMinutes % 60;

        StringBuilder result = new StringBuilder();
        if (days > 0) {
            result.append(t(local, "player-menu-time-days", args("value", days), days + "d"));
        }
        if (hours > 0) {
            if (!result.isEmpty()) result.append(' ');
            result.append(t(local, "player-menu-time-hours", args("value", hours), hours + "h"));
        }
        if (minutes > 0 || result.isEmpty()) {
            if (!result.isEmpty()) result.append(' ');
            result.append(t(local, "player-menu-time-minutes", args("value", minutes), minutes + "m"));
        }

        return result.toString();
    }

    public static String formatTimestamp(long millis) {
        if (millis <= 0) return "-";
        return DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(millis));
    }

    @Override
    public UpdateResult<ProfileModel> update(ProfileModel model, ProfileEvent event, ControllerContext ctx) {
        return switch (event) {
            case ProfileEvent.SelectTab(Tab tab) ->
                    UpdateResult.rerender(model.withTab(tab));

            case ProfileEvent.DetailsLoaded(String targetUuid, ProfileDetails details) -> {
                if (Objects.equals(model.targetUuid(), targetUuid) && details != null) {
                    yield UpdateResult.rerender(model.withDetails(details));
                }
                yield UpdateResult.of(model);
            }

            case ProfileEvent.BackToPlayers() -> {
                List<OnlinePlayerRow> rows = resolveOnlinePlayers(session, sessionService, playerDisplayService, model.adminFilter());
                int count = sessionService != null ? sessionService.getCachedCount() : rows.size();
                yield UpdateResult.rerender(model.withRefreshedPlayersPreservingPage(rows, count).withTab(Tab.PLAYERS));
            }

            case ProfileEvent.Back() -> {
                if (session != null && session.hasHistory()) {
                    Runnable prev = session.popHistory();
                    if (prev != null) {
                        if (ctx != null) {
                            ctx.post(prev);
                        } else {
                            prev.run();
                        }
                        yield UpdateResult.close(model);
                    }
                }
                yield UpdateResult.of(model);
            }

            case ProfileEvent.CycleAdminFilter() -> {
                AdminFilter nextFilter = model.adminFilter().next();
                List<OnlinePlayerRow> rows = resolveOnlinePlayers(session, sessionService, playerDisplayService, nextFilter);
                yield UpdateResult.rerender(model.withAdminFilter(nextFilter, rows));
            }

            case ProfileEvent.ChangePlayersPage(int page) -> {
                int totalPages = Math.max(1, (int) Math.ceil((double) model.onlinePlayers().size() / PLAYERS_PER_PAGE));
                int clamped = Math.clamp(page, 1, totalPages);
                yield UpdateResult.rerender(model.withPlayersPage(clamped));
            }

            case ProfileEvent.RefreshPlayers() -> {
                List<OnlinePlayerRow> rows = resolveOnlinePlayers(session, sessionService, playerDisplayService, model.adminFilter());
                int count = sessionService != null ? sessionService.getCachedCount() : rows.size();
                yield UpdateResult.rerender(model.withRefreshedPlayers(rows, count));
            }

            case ProfileEvent.InspectPlayer(String targetUuid) -> {
                PlayerData target = null;
                boolean isOnline = false;
                boolean isAdmin = false;
                if (sessionService != null) {
                    Session s = sessionService.get(targetUuid);
                    if (s != null && s.data != null) {
                        target = s.data;
                        isOnline = s.player != null;
                        isAdmin = isOnline && s.player.admin;
                    }
                }
                if (target == null && initialTargetData != null && Objects.equals(initialTargetData.uuid, targetUuid)) {
                    target = initialTargetData;
                    isOnline = session != null && session.player != null && Objects.equals(session.data.uuid, targetUuid);
                    isAdmin = isOnline && session.player.admin;
                }

                if (target == null) {
                    String err = t(session, "error-player-not-found");
                    yield UpdateResult.rerender(model.withFeedback("[scarlet]" + Iconc.warning + " " + err + "[]"));
                }

                String colorHex = PlayerSettingsUiController.resolvePlayerColorHex(session, target);
                ProfileModel newModel = model.withTarget(target, isOnline, isAdmin, colorHex, null, true);
                UpdateResult<ProfileModel> shown = UpdateResult.rerender(newModel);
                yield playerMenu != null ? shown.effect(DETAILS, playerMenu.loadDetails(session, target)) : shown;
            }

            case ProfileEvent.OpenSettings() -> {
                if (!model.isSelf() && !may(PermissionNodes.PLAYERS_SETTINGS_OTHERS)) {
                    yield UpdateResult.of(model);
                }
                PlayerData target = resolveCurrentTarget(model);
                if (target != null && playerMenu != null && session != null) {
                    playerMenu.openSettingsUi(session, target);
                }
                yield UpdateResult.close(model);
            }

            case ProfileEvent.OpenAuditHistory() -> {
                if (!may(PermissionNodes.MODERATION_AUDIT_OTHERS)) {
                    yield UpdateResult.of(model);
                }
                PlayerData target = resolveCurrentTarget(model);
                if (target != null && auditHistoryMenu != null && session != null && session.data != null) {
                    final Tab returnTab = model.tab();
                    final ProfileDetails cached = model.details();

                    session.pushHistory(() -> {
                        playerMenu.openProfileUi(session, target, returnTab, cached);
                    });
                    auditHistoryMenu.history(session.data.uuid, target);
                }
                yield UpdateResult.close(model);
            }

            case ProfileEvent.OpenAuditActions() -> {
                if (!may(PermissionNodes.MODERATION_AUDIT_OTHERS)) {
                    yield UpdateResult.of(model);
                }
                PlayerData target = resolveCurrentTarget(model);
                if (target != null && auditHistoryMenu != null && session != null && session.data != null) {
                    final Tab returnTab = model.tab();
                    final ProfileDetails cached = model.details();

                    session.pushHistory(() -> {
                        playerMenu.openProfileUi(session, target, returnTab, cached);
                    });
                    auditHistoryMenu.actions(session.data.uuid, target);
                }
                yield UpdateResult.close(model);
            }

            case ProfileEvent.OpenMatches() -> {
                PlayerData target = resolveCurrentTarget(model);
                if (!model.isSelf() || target == null || playerMenu == null || session == null) {
                    yield UpdateResult.of(model);
                }
                playerMenu.openMatches(session, target, model.tab(), model.details());
                yield UpdateResult.close(model);
            }

            case ProfileEvent.Close() -> UpdateResult.close(model);
        };
    }

    private PlayerData resolveCurrentTarget(ProfileModel model) {
        if (model == null || model.targetUuid() == null) return initialTargetData;
        if (initialTargetData != null && Objects.equals(initialTargetData.uuid, model.targetUuid())) {
            return initialTargetData;
        }
        if (sessionService != null) {
            Session s = sessionService.get(model.targetUuid());
            if (s != null && s.data != null) return s.data;
        }
        return initialTargetData;
    }

    private static final ActionRouter<ProfileEvent> ACTIONS = ActionRouter.<ProfileEvent>create()
            .on("action:close", ProfileEvent.Close::new)
            .on("action:back", ProfileEvent.Back::new)
            .on("action:settings", ProfileEvent.OpenSettings::new)
            .on("action:audit_history", ProfileEvent.OpenAuditHistory::new)
            .on("action:audit_actions", ProfileEvent.OpenAuditActions::new)
            .on("action:matches", ProfileEvent.OpenMatches::new)
            .on("action:back_to_players", ProfileEvent.BackToPlayers::new)
            .on("action:tab:overview", () -> new ProfileEvent.SelectTab(Tab.OVERVIEW))
            .on("action:tab:stats", () -> new ProfileEvent.SelectTab(Tab.STATS))
            .on("action:tab:players", () -> new ProfileEvent.SelectTab(Tab.PLAYERS))
            .on("action:filter_cycle", ProfileEvent.CycleAdminFilter::new)
            .on("action:refresh_players", ProfileEvent.RefreshPlayers::new)
            .prefix("action:page:", page -> {
                try {
                    return new ProfileEvent.ChangePlayersPage(Integer.parseInt(page));
                } catch (NumberFormatException ignored) {
                    return new ProfileEvent.RefreshPlayers();
                }
            })
            .prefix("action:inspect:", ProfileEvent.InspectPlayer::new);

    @Override
    public ProfileEvent parseEvent(MenuResult result) {
        return ACTIONS.parse(result);
    }

    @Override
    public VNode render(ProfileModel model) {
        return Screen.each(screen -> window(model, screen));
    }

    /** The window as one {@link Screen} sees it. */
    VNode window(ProfileModel model, Screen screen) {
        float width = screen.width();
        Localization local = locale(session);
        boolean players = model.tab() == Tab.PLAYERS;

        return Kit.window(window -> {
            String title = "[orange]" + Iconc.players + "[] [white]"
                    + t(local, players ? "player-menu-players-title" : "player-stats-title") + "[]";
            String status = players ? ""
                    : "\n" + identity(model, local, width - 2f * Kit.MARGIN) + "  "
                    + t(local, model.isTargetOnline() ? "player-stats-status-online" : "player-stats-status-offline");
            window.add(Kit.header(width, title + status)).row();

            window.add(Kit.tabs(width, "profile_tabs", List.of(
                    new Kit.Tab(Iconc.admin, t(local, "player-stats-tab-overview"), "action:tab:overview",
                            accent(Tab.OVERVIEW), model.tab() == Tab.OVERVIEW),
                    new Kit.Tab(Iconc.chartBar, t(local, "player-stats-tab-stats"), "action:tab:stats",
                            accent(Tab.STATS), model.tab() == Tab.STATS),
                    new Kit.Tab(Iconc.players,
                            t(local, "player-stats-tab-players", args("count", model.totalOnlineCount())), "action:tab:players",
                            accent(Tab.PLAYERS), players)))).row();
            window.add(Kit.line(width, accent(model.tab()))).row();

            Kit.body(window, screen, body -> {
                switch (model.tab()) {
                    case OVERVIEW -> overview(body, model, screen, local);
                    case STATS -> stats(body, model, screen, local);
                    case PLAYERS -> players(body, model, screen, local);
                }
            });

            if (players) {
                int pages = Math.max(1, (int) Math.ceil((double) model.onlinePlayers().size() / PLAYERS_PER_PAGE));
                int page = Math.clamp(model.playersPage(), 1, pages);
                window.add(Kit.pager(width, page + " / " + pages,
                        page > 1 ? "action:page:" + (page - 1) : null,
                        page < pages ? "action:page:" + (page + 1) : null,
                        "action:refresh_players")).row();
            }

            if (model.feedbackMessage() != null && !model.feedbackMessage().isBlank()) {
                window.add(Ui.table(feedback -> {
                    feedback.layout(l -> l.padTop(GAP));
                    feedback.add(Kit.feedback(width, model.feedbackMessage(), false));
                })).row();
            }

            List<Kit.Action> actions = actions(model, local);
            if (!actions.isEmpty()) {
                window.add(Ui.table(bar -> {
                    bar.layout(l -> l.padTop(GAP));
                    bar.add(Kit.actions(width, actions));
                })).row();
            }
        });
    }

    private static Accent accent(Tab tab) {
        return switch (tab) {
            case OVERVIEW -> Accent.GOLD;
            case STATS -> Accent.BLUE;
            case PLAYERS -> Accent.GREEN;
        };
    }

    private boolean may(String node) {
        return session != null && session.has(node);
    }

    /** The way back, and what the viewer may do with the profile on screen. The client's own button closes the dialog. */
    private List<Kit.Action> actions(ProfileModel model, Localization local) {
        List<Kit.Action> actions = new ArrayList<>();
        if (session != null && session.hasHistory()) {
            actions.add(new Kit.Action("[lightgray]" + Iconc.left + "[] " + t(local, "back"), "action:back"));
        } else if (model.viewingFromPlayersTab() && model.tab() != Tab.PLAYERS) {
            actions.add(new Kit.Action("[lightgray]" + Iconc.left + "[] " + t(local, "back"), "action:back_to_players"));
        }
        if (model.tab() != Tab.PLAYERS) {
            if (model.isSelf() && playerMenu != null && playerMenu.hasMatchHistory()) {
                actions.add(new Kit.Action("[accent]" + Iconc.list + "[] " + t(local, "player-stats-btn-matches"),
                        "action:matches"));
            }
            if (model.isSelf() || model.isViewerAdmin()) {
                actions.add(new Kit.Action("[accent]" + Iconc.settings + "[] " + t(local, "player-stats-btn-settings"),
                        "action:settings"));
            }
            if (model.isViewerAdmin()) {
                actions.add(new Kit.Action("[sky]" + Iconc.list + "[] " + t(local, "player-stats-btn-audit"),
                        "action:audit_history"));
                actions.add(new Kit.Action("[scarlet]" + Iconc.hammer + "[] " + t(local, "audit-menu-actions-open"),
                        "action:audit_actions"));
            }
        }
        return actions;
    }

    /**
     * The admin mark, the badge, the name in its own colours, the username and the player's number,
     * for a label of {@code room}.
     */
    private static String identity(ProfileModel model, Localization local, float room) {
        StringBuilder identity = new StringBuilder();
        if (model.isTargetAdmin()) {
            identity.append("[scarlet]<").append(Iconc.admin).append(' ').append(t(local, "admin")).append(">[] ");
        }
        appendName(identity, model.activeBadge(), model.badgeSymbolColorMode(), model.playerColorHex(),
                model.customNickname(), model.nickname(), "[accent]", local);
        appendUsername(identity, model.username(), room);
        return identity.append(" [gray]#").append(model.pid()).append("[]").toString();
    }

    /** A username has nowhere to break, and the longest is wider than a card of a phone. */
    private static void appendUsername(StringBuilder text, String username, float room) {
        if (username != null && !username.isBlank()) {
            text.append(" [gold]@[accent]")
                    .append(TextWidth.fit(TextWidth.escape(username), room - TextWidth.of("@")))
                    .append("[]");
        }
    }

    private static void appendName(StringBuilder text, String badgeId, String badgeColorMode, String colorHex,
                                   String customNickname, String nickname, String color, Localization local) {
        if (badgeId != null && !badgeId.isBlank()) {
            Badge badge = Badge.byId(badgeId);
            if (badge != null) {
                text.append(PlayerSettingsUiController.renderBadgeTagExact(badge, badgeColorMode, colorHex)).append(' ');
            }
        }
        String name = customNickname != null && !customNickname.isBlank()
                ? customNickname
                : (nickname != null && !nickname.isBlank() ? nickname : t(local, "player-menu-player"));
        // A name that brings its own colours keeps them.
        text.append(name.startsWith("[") ? "" : color).append(name).append("[]");
    }

    // =========================================================================
    // Tab 1: OVERVIEW
    // =========================================================================

    /** The mode sections in the viewer's language; a section that fails to render is left out. */
    private static List<ProfileSection> renderSections(ProfileModel model, Localization local) {
        List<ProfileSection> sections = new ArrayList<>();
        if (local == null) {
            return sections;
        }
        for (ProfileSectionView view : model.sections()) {
            try {
                sections.add(view.render(local));
            } catch (Exception ignored) {
                // One mode's section must not take the whole profile down.
            }
        }
        return sections;
    }

    private void overview(Ui.TableBuilder body, ProfileModel model, Screen screen, Localization local) {
        float card = screen.card();
        List<VNode> left = new ArrayList<>();
        List<VNode> right = new ArrayList<>();

        // Who this is: the bio, when the account appeared and how long it has played.
        left.add(Kit.card(card, Accent.GOLD, Iconc.players + " " + identity(model, local, card - 2f * Kit.BAND_MARGIN),
                (content, inner) -> {
            String bio = model.description() != null && !model.description().isBlank()
                    ? "[lightgray]\"" + model.description().trim() + "[lightgray]\"[]"
                    : t(local, "player-stats-no-bio");
            content.add(Kit.text(bio, inner)).row();
            content.add(Ui.table(facts -> {
                facts.layout(l -> l.padTop(GAP));
                facts.add(Kit.text(t(local, "player-stats-account-created") + " [white]"
                        + formatTimestamp(model.createdModelTime()) + "[]\n"
                        + t(local, "player-stats-play-time") + " [white]"
                        + formatDuration(model.totalPlayTime(), local) + "[]", inner));
            })).row();
        }));

        // What the player has done so far: wins out of games, so the rate reads at a glance, and blocks built.
        if (model.isStatsLoading()) {
            left.add(Kit.note(card, t(local, "player-stats-loading")));
        } else if (model.stats() == null || hasNoGames(model.stats())) {
            left.add(Kit.note(card, t(local, "player-stats-no-stats")));
        } else if ("ready".equals(model.stats().status())) {
            AggregatedPlayerStats overall = model.stats().overall();
            NumberFormat nf = Localization.integers(local != null ? local.getLocale() : Locale.ROOT);
            left.add(Kit.card(card, (content, inner) -> content.add(Kit.text(
                    victories(local, nf, overall.gamesWon(), overall.gamesPlayed(), overall.winRatePercent()) + "\n"
                            + t(local, "player-stats-all-modes-hint") + "\n"
                            + t(local, "player-stats-blocks-built") + " [lime]" + nf.format(overall.blocksBuilt()) + "[]",
                    inner)).row()));
        }

        if (!model.isStatsLoading() && model.stats() != null) {
            PlayerStatsOverview stats = model.stats();
            if (stats.updating()) left.add(Kit.note(card, t(local, "player-stats-rebuilding")));
            if ("ready".equals(stats.status()) && stats.detailed() && !hasNoGames(stats)) {
                String participation = t(local, "player-stats-participation",
                        args("left", stats.left(), "streak", stats.streak()));
                if (stats.recent().isEmpty()) {
                    left.add(Kit.note(card, participation));
                } else {
                    // A row a match, then how often the player leaves and how many wins in a row they have now.
                    long now = System.currentTimeMillis();
                    left.add(Kit.card(card, Accent.GRAY, t(local, "player-stats-recent"), (content, inner) -> {
                        for (int i = 0; i < stats.recent().size(); i++) {
                            content.add(recentMatch(stats.recent().get(i), local, inner, i % 2 == 0, now)).row();
                        }
                        content.add(divider(inner)).row();
                        content.add(Kit.centered("[lightgray]" + participation + "[]", inner)).row();
                    }));
                }
            }
        }

        // One card per section the server's modes contribute (rating ladders).
        for (ProfileSection section : renderSections(model, local)) {
            right.add(sectionCard(card, section, true));
        }
        body.add(Kit.columns(screen, left, right)).row();
    }

    // =========================================================================
    // Tab 2: STATS
    // =========================================================================

    private void stats(Ui.TableBuilder body, ProfileModel model, Screen screen, Localization local) {
        if (model.isStatsLoading()) {
            body.add(Kit.note(screen.cards(), t(local, "player-stats-loading"))).row();
            return;
        }
        // A ready projection being recalculated still shows its last figures, but says they are about to change.
        if (model.stats() != null && "ready".equals(model.stats().status()) && model.stats().updating()) {
            body.add(Kit.note(screen.cards(), t(local, "player-stats-rebuilding"))).row();
        }
        if (model.stats() == null || hasNoGames(model.stats())) {
            body.add(Kit.note(screen.cards(), t(local, "player-stats-no-stats"))).row();
            return;
        }
        if (!"ready".equals(model.stats().status())) {
            body.add(Kit.note(screen.cards(), t(local, "player-stats-rebuilding"))).row();
            return;
        }
        float card = screen.card();
        NumberFormat nf = Localization.integers(local != null ? local.getLocale() : Locale.ROOT);
        PlayerStatsOverview overview = model.stats();
        AggregatedPlayerStats overall = overview.overall();
        boolean detailed = overview.detailed();

        // Every mode together, then what a win means where it is not simply winning.
        List<Kit.Tile> tiles = new ArrayList<>(List.of(
                Kit.Tile.number(nf.format(overall.gamesPlayed()), t(local, "player-stats-tile-games")),
                Kit.Tile.number(nf.format(overall.gamesWon()) + " · " + overall.winRatePercent() + "%",
                        t(local, "player-stats-tile-wins"))));
        if (detailed) {
            tiles.add(Kit.Tile.number(nf.format(overview.left()), t(local, "player-stats-tile-left")));
            tiles.add(Kit.Tile.number(hours(overview.playMs(), nf, local), t(local, "player-stats-tile-time")));
        }
        Kit.tiles(body, screen, Accent.GOLD, tiles);
        body.add(Ui.table(hint -> {
            hint.layout(l -> l.padBottom(GAP));
            hint.add(Kit.centered("[gray]" + t(local, "player-stats-win-hint") + "[]", screen.cards()));
        })).row();

        // The rating ladders in short (the overview has the rest), a card per mode with all of its figures, the blocks.
        List<VNode> cards = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (ProfileSection section : renderSections(model, local)) {
            cards.add(sectionCard(card, section, false));
            heights.add(3);
        }

        ModeStatsSummary pvp = overview.pvp();
        if (pvp.hasData()) {
            List<Fact> facts = new ArrayList<>();
            facts.add(fact(local, "games", nf.format(pvp.gamesPlayed())));
            facts.add(fact(local, "wins", rate(nf, pvp.gamesWon(), pvp.winRatePercent())));
            participation(facts, pvp, detailed, nf, local);
            addFacts(cards, heights, card, ModeLook.PVP, facts, local);
        }
        ModeStatsSummary survival = overview.survival();
        if (survival.hasData()) {
            List<Fact> facts = new ArrayList<>();
            facts.add(fact(local, "games", nf.format(survival.gamesPlayed())));
            if (detailed) facts.add(fact(local, "completed", nf.format(survival.gamesPlayed() - survival.left())
                    + " [gray]/ " + nf.format(survival.gamesPlayed()) + "[]"));
            facts.add(fact(local, "last-wave", rate(nf, survival.gamesWon(), survival.winRatePercent())));
            if (survival.bestWave() > 0) facts.add(fact(local, "waves", "[lime]" + nf.format(survival.bestWave())
                    + "[] · " + nf.format(survival.averageWave())));
            participation(facts, survival, detailed, nf, local);
            addFacts(cards, heights, card, ModeLook.SURVIVAL, facts, local);
        }
        ModeStatsSummary hexed = overview.hexed();
        if (hexed.hasData()) {
            List<Fact> facts = new ArrayList<>();
            facts.add(fact(local, "games", nf.format(hexed.gamesPlayed())));
            facts.add(fact(local, "firsts", rate(nf, hexed.gamesWon(), hexed.winRatePercent())));
            if (hexed.bestPlacement() > 0) facts.add(fact(local, "best-place", "[accent]#" + hexed.bestPlacement()
                    + "[] · " + nf.format(hexed.top3Finishes())));
            participation(facts, hexed, detailed, nf, local);
            addFacts(cards, heights, card, ModeLook.HEXED, facts, local);
        }
        // Tower defence is endless, so the waves held are its result; wins would only ever be zero.
        ModeStatsSummary towerDefense = overview.towerDefense();
        if (towerDefense.hasData()) {
            List<Fact> facts = new ArrayList<>();
            facts.add(fact(local, "games", nf.format(towerDefense.gamesPlayed())));
            if (towerDefense.bestWave() > 0) facts.add(fact(local, "waves", "[lime]" + nf.format(towerDefense.bestWave())
                    + "[] · " + nf.format(towerDefense.averageWave())));
            participation(facts, towerDefense, detailed, nf, local);
            addFacts(cards, heights, card, ModeLook.TOWER_DEFENSE, facts, local);
        }
        ModeStatsSummary rvsb = overview.rvsb();
        if (rvsb.hasData()) {
            List<Fact> facts = new ArrayList<>();
            facts.add(fact(local, "games", nf.format(rvsb.gamesPlayed())));
            facts.add(fact(local, "wins", rate(nf, rvsb.gamesWon(), rvsb.winRatePercent())));
            participation(facts, rvsb, detailed, nf, local);
            addFacts(cards, heights, card, ModeLook.RVSB, facts, local);
        }
        long otherGames = overview.otherGamesPlayed();
        if (otherGames > 0) {
            long otherWins = overview.otherGamesWon();
            List<Fact> facts = new ArrayList<>();
            facts.add(fact(local, "games", nf.format(otherGames)));
            facts.add(fact(local, "wins", rate(nf, otherWins, Math.round(otherWins * 100f / otherGames))));
            long otherMs = overview.otherPlayMs();
            if (detailed && otherMs > 0) facts.add(fact(local, "time", hours(otherMs, nf, local)));
            addFacts(cards, heights, card, ModeLook.EVENT, facts, local);
        }

        // What was built and taken apart; only the legacy reader counts destroyed blocks and units.
        List<Fact> blocks = new ArrayList<>(List.of(
                fact(local, "built", "[lime]" + nf.format(overall.blocksBuilt()) + "[]"),
                fact(local, "deconstructed", "[orange]" + nf.format(overall.blocksDeconstructed()) + "[]")));
        if (!detailed) {
            blocks.add(fact(local, "destroyed", "[scarlet]" + nf.format(overall.blocksDestroyed()) + "[]"));
            if (overall.unitsProduced() > 0 || overall.unitsDestroyed() > 0) {
                blocks.add(fact(local, "units-produced", "[sky]" + nf.format(overall.unitsProduced()) + "[]"));
                blocks.add(fact(local, "units-lost", "[scarlet]" + nf.format(overall.unitsDestroyed()) + "[]"));
            }
        }
        cards.add(Kit.card(card, Accent.BLUE, Iconc.hammer + " " + t(local, "player-stats-building"), (content, inner) -> {
            for (Fact fact : blocks) content.add(factRow(fact, inner)).row();
            if (!detailed) content.add(Kit.text(renderBlockRatioBar(overall.blocksBuilt(), overall.blocksDeconstructed(),
                    overall.blocksDestroyed(), 10) + "  " + t(local, "player-stats-ratio-legend"), inner)).row();
        }));
        heights.add(2 + blocks.size() + (detailed ? 0 : 1));

        body.add(balanced(screen, cards, heights)).row();
    }

    /** How a stats category is shown: its icon, colour and name. */
    private enum ModeLook {
        PVP(Iconc.modePvp, Accent.RED, "player-stats-mode-pvp"),
        SURVIVAL(Iconc.defense, Accent.GREEN, "player-stats-mode-survival"),
        HEXED(Iconc.star, Accent.PURPLE, "player-stats-mode-hexed"),
        TOWER_DEFENSE(Iconc.turret, Accent.TEAL, "player-stats-mode-tower-defense"),
        RVSB(Iconc.planeOutline, Accent.BLUE, "player-stats-mode-rvsb"),
        EVENT(Iconc.effect, Accent.ORANGE, "player-stats-mode-other");

        final char icon;
        final Accent accent;
        final String key;

        ModeLook(char icon, Accent accent, String key) {
            this.icon = icon;
            this.accent = accent;
            this.key = key;
        }

        /** Events and any category this build does not know yet share one look. */
        static ModeLook of(String category) {
            for (ModeLook look : values()) {
                if (look.name().equals(category)) return look;
            }
            return EVENT;
        }
    }

    /** A labelled figure in a mode's card. */
    private record Fact(String label, String value) {}

    private static Fact fact(Localization local, String row, String value) {
        return new Fact(t(local, "player-stats-row-" + row), value);
    }

    /** "160 (53%)": a count of wins with its rate. */
    private static String rate(NumberFormat nf, long wins, long percent) {
        return "[lime]" + nf.format(wins) + "[] [gray](" + percent + "%)[]";
    }

    /** Leaves, the best streak and the time played: only the projection records them. */
    private static void participation(List<Fact> facts, ModeStatsSummary mode, boolean detailed, NumberFormat nf,
                                      Localization local) {
        if (!detailed) return;
        facts.add(fact(local, "left", nf.format(mode.left())));
        facts.add(fact(local, "best-streak", nf.format(mode.bestStreak())));
        if (mode.playMs() > 0) facts.add(fact(local, "time", hours(mode.playMs(), nf, local)));
    }

    private static void addFacts(List<VNode> cards, List<Integer> heights, float width, ModeLook mode,
                                 List<Fact> facts, Localization local) {
        cards.add(Kit.card(width, mode.accent, mode.icon + " " + t(local, mode.key), (content, inner) -> {
            for (Fact fact : facts) content.add(factRow(fact, inner)).row();
        }));
        heights.add(2 + facts.size());
    }

    /** The label on the left, the figure against the right edge. */
    private static VNode factRow(Fact fact, float width) {
        float valueWidth = Math.min(width / 2f, TextWidth.of(fact.value()) + GAP);
        return Ui.table(row -> {
            row.add(Kit.text("[gray]" + fact.label() + "[]", width - valueWidth));
            row.add(Kit.right("[white]" + fact.value() + "[]", valueWidth));
        });
    }

    /** Whole hours, or minutes while there is less than an hour. */
    private static String hours(long ms, NumberFormat nf, Localization local) {
        long minutes = ms / 60_000;
        if (minutes < 60) return t(local, "player-menu-time-minutes", args("value", minutes), minutes + "m");
        String value = nf.format(minutes / 60);
        return t(local, "player-menu-time-hours", args("value", value), value + "h");
    }

    /** One column keeps the cards in order; two put each card under the shorter column so far. */
    private static VNode balanced(Screen screen, List<VNode> cards, List<Integer> heights) {
        if (screen.columns() == 1) return Kit.columns(screen, cards);
        List<VNode> left = new ArrayList<>();
        List<VNode> right = new ArrayList<>();
        int leftHeight = 0;
        int rightHeight = 0;
        for (int i = 0; i < cards.size(); i++) {
            if (leftHeight <= rightHeight) {
                left.add(cards.get(i));
                leftHeight += heights.get(i);
            } else {
                right.add(cards.get(i));
                rightHeight += heights.get(i);
            }
        }
        return Kit.columns(screen, left, right);
    }

    /** A section a mode adds to the profile, such as a rating ladder, as a card of its own; in short, its headline and details. */
    private static VNode sectionCard(float width, ProfileSection section, boolean withLines) {
        return Kit.card(width, (content, inner) -> {
            StringBuilder text = new StringBuilder(section.headline());
            if (!section.details().isEmpty()) {
                text.append('\n').append(String.join(SEPARATOR, section.details()));
            }
            if (withLines) {
                for (String line : section.lines()) {
                    text.append('\n').append(line);
                }
            }
            content.add(Kit.text(text.toString(), inner)).row();
        });
    }

    private static VNode divider(float width) {
        return Ui.image("whiteui", l -> l.width(width).height(1f).padTop(GAP).padBottom(GAP).color(Accent.GRAY.band()));
    }

    /** A ready projection without games: a newcomer, or someone whose games all were excluded. */
    private static boolean hasNoGames(PlayerStatsOverview stats) {
        return "ready".equals(stats.status()) && stats.overall().gamesPlayed() == 0;
    }

    private static final float RECENT_MARGIN = 4f;
    private static final float RECENT_ICON = 24f;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final DateTimeFormatter OLD_DAY = DateTimeFormatter.ofPattern("dd.MM.yy");

    /**
     * The mode's icon, the map with the date under it, and the result against the right edge.
     * The map name comes from the database, so it is escaped.
     */
    private static VNode recentMatch(PlayerStatsOverview.RecentMatch match, Localization local, float width,
                                     boolean shaded, long now) {
        ModeLook mode = ModeLook.of(match.category());
        String outcome = match.outcome() == null ? "not_counted" : match.outcome().toLowerCase(Locale.ROOT);
        String result = switch (outcome) {
            case "win" -> "[lime]";
            case "loss" -> "[scarlet]";
            case "left" -> "[lightgray]";
            default -> "[gray]";
        } + t(local, "player-stats-outcome-" + outcome) + "[]";
        float resultWidth = TextWidth.of(result) + GAP;
        float middle = Math.max(GAP, width - 2f * RECENT_MARGIN - RECENT_ICON - resultWidth);
        String map = match.map() == null || match.map().isBlank()
                ? "[lightgray]" + t(local, mode.key) + "[]"
                : "[white]" + TextWidth.escape(match.map()) + "[]";
        String full = matchDate(match.endedAt(), now, local, true);
        String date = TextWidth.of(full) <= middle ? full : matchDate(match.endedAt(), now, local, false);
        return Ui.table(row -> {
            row.background("whiteui");
            row.margin(RECENT_MARGIN);
            row.layout(l -> l.width(width).color(shaded ? Kit.HEADER : Kit.CARD));
            row.add(Kit.text("[#" + mode.accent.color() + "]" + mode.icon + "[]", RECENT_ICON));
            row.add(Ui.table(place -> {
                place.add(Kit.text(TextWidth.fit(map, middle), middle)).row();
                place.add(Kit.text("[gray]" + date + "[]", middle)).row();
            }));
            row.add(Kit.right(result, resultWidth));
        });
    }

    /** "today 14:05", "06.11 19:40" or "11.10.25"; the short form keeps the time alone for today and the day alone for later. */
    static String matchDate(long endedAt, long now, Localization local, boolean full) {
        if (endedAt <= 0) return "-";
        // The server's zone, as every other date in the profile; the client's zone is not known here.
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime at = LocalDateTime.ofInstant(Instant.ofEpochMilli(endedAt), zone);
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone);
        String time = TIME.format(at);
        if (at.toLocalDate().equals(today)) return full ? t(local, "player-stats-today", args("time", time)) : time;
        if (full && at.toLocalDate().equals(today.minusDays(1))) {
            return t(local, "player-stats-yesterday", args("time", time));
        }
        if (at.getYear() != today.getYear()) return OLD_DAY.format(at);
        return full ? DAY_TIME.format(at) : DAY.format(at);
    }

    /** "Wins: 777 of 1,736 | 45% win rate": the rate next to both numbers it comes from. */
    private static String victories(Localization local, NumberFormat nf, long wins, long games, int winRate) {
        return t(local, "player-stats-victories-value",
                args("wins", nf.format(wins), "games", nf.format(games), "winRate", winRate));
    }

    // =========================================================================
    // Tab 3: PLAYERS (Live Online Server Roster)
    // =========================================================================

    private void players(Ui.TableBuilder body, ProfileModel model, Screen screen, Localization local) {
        float width = screen.cards();
        List<OnlinePlayerRow> online = model.onlinePlayers() != null ? model.onlinePlayers() : List.of();
        int pages = Math.max(1, (int) Math.ceil((double) online.size() / PLAYERS_PER_PAGE));
        int page = Math.clamp(model.playersPage(), 1, pages);

        // Who is shown: everyone, the admins or the rest. A press steps to the next.
        body.add(Ui.table(filter -> {
            filter.layout(l -> l.padBottom(GAP));
            filter.add(Kit.button(new Kit.Action("[accent]" + Iconc.filter + "[] " + t(local, switch (model.adminFilter()) {
                case ALL -> "player-stats-filter-all";
                case ADMINS_ONLY -> "player-stats-filter-admins";
                case NON_ADMINS -> "player-stats-filter-non-admins";
            }), "action:filter_cycle"), width));
        })).row();

        List<OnlinePlayerRow> shown = online.stream()
                .skip((long) (page - 1) * PLAYERS_PER_PAGE)
                .limit(PLAYERS_PER_PAGE)
                .toList();
        if (shown.isEmpty()) {
            body.add(Kit.note(width, t(local, "player-menu-players-empty"))).row();
            return;
        }
        for (OnlinePlayerRow player : shown) {
            // The whole row opens the player's profile.
            body.add(Kit.row("action:inspect:" + player.uuid(), width,
                    Objects.equals(player.uuid(), model.viewerUuid()), true, (row, inner) -> {
                        StringBuilder name = new StringBuilder("[lime]\u25cf[] ");
                        if (player.isAdmin()) {
                            name.append("[scarlet]<").append(Iconc.admin).append(">[] ");
                        }
                        appendName(name, player.activeBadge(), player.badgeSymbolColorMode(), player.playerColorHex(),
                                player.customNickname(), player.nickname(), "[white]", local);
                        appendUsername(name, player.username(), inner);
                        name.append(" [gray]#").append(player.pid()).append("[]");
                        row.add(Kit.text(TextWidth.fit(name.toString(), inner), inner));
                    })).row();
        }
    }
}
