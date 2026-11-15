package org.xcore.plugin.service;

import arc.Events;
import com.ospx.flubundle.Bundle;
import io.avaje.inject.BeanScope;
import io.avaje.inject.spi.AvajeModule;
import io.avaje.inject.spi.Builder;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.gen.Player;
import org.junit.jupiter.api.Test;
import org.xcore.plugin.config.TomlSecretsConfig;
import org.xcore.plugin.database.repository.PlayerDataRepository;
import org.xcore.plugin.database.repository.StaffActivityStore;
import org.xcore.plugin.model.PlayerData;
import org.xcore.plugin.permission.StaffAccess;
import org.xcore.plugin.session.Session;
import org.xcore.plugin.session.SessionService;
import org.xcore.plugin.ui.MenuService;
import org.xcore.testkit.fixtures.HeadlessWorld;
import org.xcore.testkit.fixtures.junit.WithHeadlessWorld;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@WithHeadlessWorld
class PlayerActivityServiceAvajeTest {
    private static BeanScope scope() {
        return BeanScope.builder().bean(TomlSecretsConfig.class, new TomlSecretsConfig())
                .modules(new AvajeModule() {
                    @Override
                    public Class<?>[] classes() { return new Class<?>[]{PlayerActivityService.class}; }

                    @Override
                    public void build(Builder builder) { PlayerActivityService$DI.build(builder); }
                }).forTesting()
                .mock(SessionService.class)
                .mock(FindService.class)
                .mock(StaffAccess.class)
                .mock(StaffActivityStore.class)
                .build();
    }

    @Test
    void theMinuteTickStopsCreditingAnIdlePlayerUntilTheyChat(HeadlessWorld world) {
        var mainLoop = world.mainLoop();
        var timer = world.timer();
        try (var scope = scope()) {
            var sessions = scope.get(SessionService.class);
            Player player = Player.create();
            var data = new PlayerData();
            data.uuid = "idle-uuid";
            var session = new Session(new TomlSecretsConfig(), mock(Bundle.class),
                    mock(MenuService.class), mock(PlayerDataRepository.class), player, data);
            when(sessions.getAllCachedSnapshot()).thenReturn(List.of(session));
            when(sessions.get(player.uuid())).thenReturn(session);
            when(scope.get(FindService.class).playerByUuid("idle-uuid")).thenReturn(player);
            Runnable minute = () -> {
                timer.advance(60f);
                mainLoop.runUntilIdle();
            };

            timer.runDue();
            mainLoop.runUntilIdle();
            for (int i = 0; i < 29; i++) minute.run();
            verify(sessions, times(PlayerActivityService.AFK_AFTER_MINUTES))
                    .incrementPlayTimeAsync(same(session), anyInt());

            clearInvocations(sessions);
            Events.fire(new PlayerChatEvent(player, "back"));
            minute.run();
            verify(sessions).incrementPlayTimeAsync(session, 1);

            clearInvocations(sessions);
            for (int i = 0; i < 10; i++) minute.run();
            verify(sessions, times(PlayerActivityService.AFK_AFTER_MINUTES - 1))
                    .incrementPlayTimeAsync(same(session), anyInt());
        }
    }

    @Test
    void anActiveMinuteOfLinkedStaffIsAddedToStaffActivity(HeadlessWorld world) {
        var mainLoop = world.mainLoop();
        var timer = world.timer(); // before the scope, or its tick goes to Arc's real timer thread
        try (var scope = scope()) {
            var sessions = scope.get(SessionService.class);
            var staff = scope.get(StaffAccess.class);
            var store = scope.get(StaffActivityStore.class);
            var admin = session(scope, "admin-uuid", "769");
            var unlinked = session(scope, "unlinked-uuid", "");
            var player = session(scope, "player-uuid", "530");
            when(sessions.getAllCachedSnapshot()).thenReturn(List.of(admin, unlinked, player));
            when(staff.mayLogIn(admin)).thenReturn(true);
            when(staff.mayLogIn(unlinked)).thenReturn(true);
            when(staff.isLoggedIn(admin)).thenReturn(true);

            timer.runDue();
            mainLoop.runUntilIdle();

            verify(store).addMinute(eq("769"), eq(true), any());
            verifyNoMoreInteractions(store);
        }
    }

    @Test
    void anIdleStaffMinuteIsNotAddedToStaffActivity(HeadlessWorld world) {
        var mainLoop = world.mainLoop();
        var timer = world.timer();
        try (var scope = scope()) {
            var admin = session(scope, "admin-uuid", "769");
            when(scope.get(SessionService.class).getAllCachedSnapshot()).thenReturn(List.of(admin));
            when(scope.get(StaffAccess.class).mayLogIn(admin)).thenReturn(true);

            timer.runDue();
            mainLoop.runUntilIdle();
            for (int i = 0; i < 29; i++) {
                timer.advance(60f);
                mainLoop.runUntilIdle();
            }

            verify(scope.get(StaffActivityStore.class), times(PlayerActivityService.AFK_AFTER_MINUTES))
                    .addMinute(eq("769"), eq(false), any());
        }
    }

    private static Session session(BeanScope scope, String uuid, String discordId) {
        Player player = Player.create();
        var data = new PlayerData();
        data.uuid = uuid;
        data.discordId = discordId;
        var session = new Session(new TomlSecretsConfig(), mock(Bundle.class),
                mock(MenuService.class), mock(PlayerDataRepository.class), player, data);
        when(scope.get(FindService.class).playerByUuid(uuid)).thenReturn(player);
        return session;
    }

    @Test
    void closingScopeCancelsTimerAndDisablesAnAlreadyQueuedTick(HeadlessWorld world) {
        var mainLoop = world.mainLoop();
        var timer = world.timer();
        var scope = scope();
        timer.runDue(); // the first tick is posted to the main loop and waits there
        assertThat(mainLoop.size()).isEqualTo(1);

        scope.close();
        mainLoop.runUntilIdle();
        timer.advance(120f);
        mainLoop.runUntilIdle();

        assertThat(timer.pending()).as("the 60 s tick is cancelled").isZero();
        verifyNoInteractions(scope.get(SessionService.class), scope.get(FindService.class));
    }
}
