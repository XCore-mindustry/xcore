package org.xcore.plugin.command.controller.client;

import mindustry.gen.Player;
import mindustry.net.NetConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xcore.plugin.cloud.XCoreSender;
import org.xcore.plugin.concurrent.MainThreadDispatcher;
import org.xcore.plugin.concurrent.StorageExecutor;
import org.xcore.plugin.localization.Localization;
import org.xcore.plugin.model.PlayerData;
import org.xcore.plugin.permission.StaffEnrollments;
import org.xcore.plugin.service.AdminAuthService;
import org.xcore.plugin.service.AdminAuthService.PasswordVerificationResult;
import org.xcore.plugin.service.PlayerDisplayService;
import org.xcore.plugin.session.Session;
import org.xcore.plugin.session.SessionService;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthControllerTest {

    private AdminAuthService adminAuthService;
    private SessionService sessionService;
    private PlayerDisplayService playerDisplayService;
    private StorageExecutor storageExecutor;
    private AuthController controller;
    private StaffEnrollments enrollments;

    @BeforeEach
    void setUp() {
        adminAuthService = mock(AdminAuthService.class);
        sessionService = mock(SessionService.class);
        playerDisplayService = mock(PlayerDisplayService.class);
        // Use single-thread StorageExecutor for predictable testing
        storageExecutor = new StorageExecutor(4);

        // Synchronous main-thread dispatcher for immediate continuation in tests
        MainThreadDispatcher synchronousMainThread = Runnable::run;

        enrollments = new StaffEnrollments(Clock.systemUTC());
        controller = new AuthController(
                adminAuthService,
                sessionService,
                playerDisplayService,
                storageExecutor,
                synchronousMainThread,
                enrollments
        );
    }

    @AfterEach
    void tearDown() {
        if (storageExecutor != null) {
            storageExecutor.shutdown();
        }
    }

    private TestContext createContext(String uuid, boolean online) {
        Player player = mock(Player.class);
        when(player.uuid()).thenReturn(uuid);
        when(player.plainName()).thenReturn("Tester-" + uuid);
        when(player.isAdded()).thenReturn(online);

        NetConnection con = mock(NetConnection.class);
        when(con.isConnected()).thenReturn(online);
        player.con = con;

        PlayerData data = new PlayerData();
        data.uuid = uuid;

        Localization local = mock(Localization.class);
        Session session = mock(Session.class);
        session.player = player;
        session.data = data;
        when(session.locale()).thenReturn(local);

        XCoreSender sender = mock(XCoreSender.class);
        when(sender.session()).thenReturn(session);
        when(sender.player()).thenReturn(player);

        when(sessionService.get(uuid)).thenReturn(session);

        return new TestContext(sender, session, player, data, local);
    }

    private record TestContext(
            XCoreSender sender,
            Session session,
            Player player,
            PlayerData data,
            Localization local
    ) {}

    @Test
    @DisplayName("successful login sends immediate ack, executes off-thread, and grants admin on tick thread")
    void login_successfulAuthentication() throws Exception {
        var ctx = createContext("uuid-1", true);
        ctx.data().password = "stored-hash";
        when(adminAuthService.mayLogInAsStaff(ctx.data())).thenReturn(true);
        when(adminAuthService.checkPassword(argThat(sameIdentityAs(ctx.data())), eq("secret123")))
                .thenReturn(new PasswordVerificationResult(true, false, "commands-login-success"));

        controller.login(ctx.sender(), "secret123");

        // Wait briefly for background execution
        org.testcontainers.shaded.org.awaitility.Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    verify(ctx.local()).send(eq("commands-login-verifying"), any());
                    verify(adminAuthService).grantAdmin(ctx.player(), ctx.session());
                    verify(ctx.local()).send(eq("commands-login-success"), any());
                    assertThat(controller.isInFlight("uuid-1")).isFalse();
                    assertThat(controller.getFailedAttempts("uuid-1")).isZero();
                });
    }

    @Test
    @DisplayName("a password reset while the login was being checked leaves the player without admin")
    void login_passwordResetMeanwhile() {
        var ctx = createContext("uuid-reset", true);
        ctx.data().password = "stored-hash";
        when(adminAuthService.mayLogInAsStaff(ctx.data())).thenReturn(true);
        when(adminAuthService.checkPassword(argThat(sameIdentityAs(ctx.data())), eq("secret123")))
                .thenAnswer(_ -> {
                    ctx.data().password = null;
                    return new PasswordVerificationResult(true, false, "commands-login-success");
                });

        controller.login(ctx.sender(), "secret123");

        org.testcontainers.shaded.org.awaitility.Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    verify(ctx.local()).send(eq("error-processing-request"), any());
                    assertThat(controller.isInFlight("uuid-reset")).isFalse();
                });
        verify(adminAuthService, never()).grantAdmin(any(), any());
    }

    @Test
    @DisplayName("losing the last staff role while the first password is hashed refuses the setup and keeps no password")
    void staffSetup_staffRoleRemovedMeanwhile() {
        var ctx = createContext("uuid-demoted", true);
        when(adminAuthService.mayLogInAsStaff(ctx.data())).thenReturn(true, false);
        when(adminAuthService.createPassword(argThat(sameIdentityAs(ctx.data())), eq("newPassword123")))
                .thenReturn(new PasswordVerificationResult(true, true, "commands-login-admin-password-created"));
        String code = enrollments.issue(ctx.player());

        controller.staffSetup(ctx.sender(), code, "newPassword123");

        org.testcontainers.shaded.org.awaitility.Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    verify(ctx.local()).send(eq("commands-login-request-approval-discord"), any());
                    assertThat(controller.isInFlight("uuid-demoted")).isFalse();
                });
        verify(adminAuthService, never()).grantAdmin(any(), any());
        verify(sessionService, never()).persistData(any());
        assertThat(ctx.data().password).isNullOrEmpty();
        assertThat(enrollments.pending(ctx.player())).as("the code is not spent").isTrue();
    }

    @Test
    @DisplayName("/login on a staff account without a password sends the player to /staff-setup and hashes nothing")
    void login_withoutPasswordNeedsSetup() {
        var ctx = createContext("uuid-create", true);
        when(adminAuthService.mayLogInAsStaff(ctx.data())).thenReturn(true);

        controller.login(ctx.sender(), "newPassword123");

        verify(ctx.local()).send(eq("commands-login-setup-required"), any());
        verify(adminAuthService, never()).checkPassword(any(), anyString());
        verify(adminAuthService, never()).grantAdmin(any(), any());
        assertThat(controller.isInFlight("uuid-create")).isFalse();
        assertThat(controller.getFailedAttempts("uuid-create")).isZero();
    }

    @Test
    @DisplayName("/staff-setup with the code sent to this connection sets the password once and logs in")
    void staffSetup_setsThePasswordOnce() throws InterruptedException {
        var ctx = createContext("uuid-setup", true);
        when(adminAuthService.mayLogInAsStaff(ctx.data())).thenReturn(true);
        CountDownLatch persisted = new CountDownLatch(1);
        when(sessionService.persistData(any())).thenAnswer(invocation -> {
            persisted.countDown();
            return true;
        });
        when(adminAuthService.createPassword(any(), eq("newPassword123"))).thenAnswer(invocation -> {
            // The storage stage writes only the copy it was handed.
            ((PlayerData) invocation.getArgument(0)).password = "new-bcrypt-hash";
            return new PasswordVerificationResult(true, true, "commands-login-admin-password-created");
        });
        String code = enrollments.issue(ctx.player());

        controller.staffSetup(ctx.sender(), code.toLowerCase(), "newPassword123");

        assertThat(persisted.await(2, TimeUnit.SECONDS)).isTrue();
        org.testcontainers.shaded.org.awaitility.Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    verify(adminAuthService).grantAdmin(ctx.player(), ctx.session());
                    verify(ctx.local()).send(eq("commands-login-admin-password-created"), any());
                    assertThat(controller.isInFlight("uuid-setup")).isFalse();
                });
        assertThat(ctx.data().password).isEqualTo("new-bcrypt-hash");
        assertThat(enrollments.pending(ctx.player())).as("the code is spent").isFalse();

        controller.staffSetup(ctx.sender(), code, "otherPassword123");
        verify(ctx.local()).send(eq("commands-staff-setup-has-password"), any());
    }

    @Test
    @DisplayName("/staff-setup with a wrong code, or a code sent to another connection, sets nothing")
    void staffSetup_wrongCodeOrConnection() {
        var ctx = createContext("uuid-forged", true);
        when(adminAuthService.mayLogInAsStaff(ctx.data())).thenReturn(true);
        String code = enrollments.issue(ctx.player());

        controller.staffSetup(ctx.sender(), "WRONGCODE2", "newPassword123");
        verify(ctx.local()).send(eq("commands-staff-setup-invalid-code"), any());
        assertThat(controller.getFailedAttempts("uuid-forged")).isEqualTo(1);

        // A forged client with the same uuid is another connection.
        NetConnection forged = mock(NetConnection.class);
        when(forged.isConnected()).thenReturn(true);
        ctx.player().con = forged;
        controller.staffSetup(ctx.sender(), code, "newPassword123");

        verify(ctx.local(), times(2)).send(eq("commands-staff-setup-invalid-code"), any());
        verify(adminAuthService, never()).createPassword(any(), anyString());
        assertThat(ctx.data().password).isNullOrEmpty();
    }

    @Test
    @DisplayName("wrong password increments failure counter, does not grant admin, and sends error")
    void login_wrongPassword() {
        var ctx = createContext("uuid-wrong", true);
        ctx.data().password = "stored-hash";
        when(adminAuthService.mayLogInAsStaff(ctx.data())).thenReturn(true);
        when(adminAuthService.checkPassword(argThat(sameIdentityAs(ctx.data())), eq("wrongPassword123")))
                .thenReturn(new PasswordVerificationResult(false, false, "error-wrong-admin-password"));

        controller.login(ctx.sender(), "wrongPassword123");

        org.testcontainers.shaded.org.awaitility.Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    verify(ctx.local()).send(eq("commands-login-verifying"), any());
                    verify(adminAuthService, never()).grantAdmin(any(), any());
                    verify(ctx.local()).send(eq("error-wrong-admin-password"), any());
                    assertThat(controller.getFailedAttempts("uuid-wrong")).isEqualTo(1);
                    assertThat(controller.isInFlight("uuid-wrong")).isFalse();
                });
    }

    @Test
    @DisplayName("concurrent login submissions for same player are rejected with in-flight message")
    void login_concurrentSubmissions_rejectsSecond() throws Exception {
        var ctx = createContext("uuid-concurrent", true);
        ctx.data().password = "stored-hash";
        when(adminAuthService.mayLogInAsStaff(ctx.data())).thenReturn(true);

        CountDownLatch holdBackground = new CountDownLatch(1);
        CountDownLatch backgroundStarted = new CountDownLatch(1);

        when(adminAuthService.checkPassword(argThat(sameIdentityAs(ctx.data())), eq("password123"))).thenAnswer(inv -> {
            backgroundStarted.countDown();
            holdBackground.await(3, TimeUnit.SECONDS);
            return new PasswordVerificationResult(true, false, "commands-login-success");
        });

        // First login starts in background
        controller.login(ctx.sender(), "password123");

        assertThat(backgroundStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(controller.isInFlight("uuid-concurrent")).isTrue();

        // Second concurrent login should immediately be rejected
        controller.login(ctx.sender(), "password123");
        verify(ctx.local()).send(eq("commands-login-already-processing"), any());

        // Release the background task
        holdBackground.countDown();

        org.testcontainers.shaded.org.awaitility.Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(controller.isInFlight("uuid-concurrent")).isFalse());
    }

    @Test
    @DisplayName("anti-brute-force gate locks out after N failures and skips BCrypt entirely")
    void login_bruteForceLockout() {
        var ctx = createContext("uuid-brute", true);
        ctx.data().password = "stored-hash";
        when(adminAuthService.mayLogInAsStaff(ctx.data())).thenReturn(true);
        when(adminAuthService.checkPassword(argThat(sameIdentityAs(ctx.data())), eq("badPassword1")))
                .thenReturn(new PasswordVerificationResult(false, false, "error-wrong-admin-password"));

        // Fail 5 times sequentially
        for (int i = 0; i < AuthController.MAX_FAILED_ATTEMPTS; i++) {
            final int expectedFailures = i + 1;
            controller.login(ctx.sender(), "badPassword1");
            org.testcontainers.shaded.org.awaitility.Awaitility.await()
                    .atMost(2, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(controller.getFailedAttempts("uuid-brute"))
                            .isEqualTo(expectedFailures));
        }

        assertThat(controller.isLockedOut("uuid-brute")).isTrue();
        reset(adminAuthService); // Clear mock invocations

        // 6th attempt while locked out: rejected immediately without invoking BCrypt
        controller.login(ctx.sender(), "badPassword1");

        verify(ctx.local()).send(eq("commands-login-rate-limited"), any());
        verify(adminAuthService, never()).checkPassword(any(), any());
        assertThat(controller.isInFlight("uuid-brute")).isFalse();
    }

    @Test
    @DisplayName("player disconnect while verifying does not grant admin or touch player")
    void login_disconnectedPlayer_doesNotGrantAdmin() throws Exception {
        var ctx = createContext("uuid-disconnect", true);
        ctx.data().password = "stored-hash";
        when(adminAuthService.mayLogInAsStaff(ctx.data())).thenReturn(true);

        CountDownLatch holdBackground = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);

        when(adminAuthService.checkPassword(argThat(sameIdentityAs(ctx.data())), eq("password123"))).thenAnswer(inv -> {
            started.countDown();
            holdBackground.await(3, TimeUnit.SECONDS);
            return new PasswordVerificationResult(true, false, "commands-login-success");
        });

        controller.login(ctx.sender(), "password123");
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        // Player disconnects while background task is still running
        when(ctx.player().isAdded()).thenReturn(false);
        when(ctx.player().con.isConnected()).thenReturn(false);

        holdBackground.countDown();

        org.testcontainers.shaded.org.awaitility.Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    verify(adminAuthService, never()).grantAdmin(any(), any());
                    verify(ctx.local(), never()).send(eq("commands-login-success"), any());
                    assertThat(controller.isInFlight("uuid-disconnect")).isFalse();
                });
    }

    @Test
    @DisplayName("short password rejected immediately before background execution")
    void login_shortPassword() {
        var ctx = createContext("uuid-short", true);

        controller.login(ctx.sender(), "short");

        verify(ctx.local()).send(eq("error-admin-password-too-short"), any());
        verify(adminAuthService, never()).checkPassword(any(), any());
        assertThat(controller.isInFlight("uuid-short")).isFalse();
    }

    @Test
    @DisplayName("player without discord admin role rejected immediately before background execution")
    void login_noDiscordAdminRole() {
        var ctx = createContext("uuid-nodiscord", true);
        when(adminAuthService.mayLogInAsStaff(ctx.data())).thenReturn(false);

        controller.login(ctx.sender(), "validPassword123");

        verify(ctx.local()).send(eq("commands-login-request-approval-discord"), any());
        verify(adminAuthService, never()).checkPassword(any(), any());
        assertThat(controller.isInFlight("uuid-nodiscord")).isFalse();
    }

    @Test
    @DisplayName("the storage stage never receives the live session data object")
    void login_doesNotHandLiveSessionDataToStorage() throws InterruptedException {
        // checkPassword hashes into whatever PlayerData it is given, and
        // session.data is live state the tick loop and every command read. Handing it over
        // makes BCrypt a cross-thread write to shared state, and if the session's data is
        // swapped while the hash is being computed the new password is written to an object
        // nothing reads any more.
        TestContext context = createContext("uuid-1", true);
        context.data().password = "stored-hash";
        when(adminAuthService.mayLogInAsStaff(any())).thenReturn(true);

        AtomicReference<PlayerData> passedToStorage = new AtomicReference<>();
        CountDownLatch reachedStorage = new CountDownLatch(1);
        when(adminAuthService.checkPassword(any(), anyString())).thenAnswer(invocation -> {
            passedToStorage.set(invocation.getArgument(0));
            reachedStorage.countDown();
            return new PasswordVerificationResult(true, false, "commands-login-success");
        });

        controller.login(context.sender(), "hunter2hunter2");
        // The executor is a real pool, so the assertion cannot outrun the storage stage.
        assertThat(reachedStorage.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(passedToStorage.get()).isNotNull();
        assertThat(passedToStorage.get())
                .as("storage must get a detached copy, not the live session object")
                .isNotSameAs(context.data());
        assertThat(passedToStorage.get().getUuid()).isEqualTo("uuid-1");
    }

    /**
     * The login path hands the storage stage a detached copy of the session data, carrying
     * the identity and password hash it needs and nothing else. Stubs match on that content
     * rather than on object identity, because identity is precisely what the controller is
     * no longer allowed to pass along.
     */
    private static org.mockito.ArgumentMatcher<PlayerData> sameIdentityAs(PlayerData expected) {
        return candidate -> candidate != null
                && java.util.Objects.equals(candidate.getUuid(), expected.getUuid())
                && java.util.Objects.equals(candidate.getPassword(), expected.getPassword());
    }

}
