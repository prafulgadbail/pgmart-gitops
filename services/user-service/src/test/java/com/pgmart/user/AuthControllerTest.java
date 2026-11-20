package com.pgmart.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthControllerTest {

    private static final String EMAIL = "asha@example.com";
    private static final String PASSWORD = "secret-pass";
    private static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);

    private final Users users = mock(Users.class);
    private final Tokens tokens = mock(Tokens.class);
    private final Mailer mailer = mock(Mailer.class);
    private final AuthController auth = new AuthController(users, tokens, mailer);

    // ---------- sign-up ----------

    @Test
    void registerCreatesAnUnverifiedAccountAndSendsACode() {
        var response = auth.register(new AuthController.RegisterRequest(" Asha ", " Asha@Example.com ", PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("email", EMAIL);
        verify(users).create(eq(EMAIL), eq("Asha"), anyString(), eq("CUSTOMER"), eq(false));
        verify(users).saveOtp(eq(EMAIL), eq("VERIFY"), anyString(), any());
        verify(mailer).sendOtp(eq(EMAIL), eq("Asha"), matches("\\d{6}"), eq("VERIFY"));
    }

    @Test
    void registerRestartsASignUpThatWasNeverVerified() {
        when(users.byEmail(EMAIL)).thenReturn(Optional.of(user(false)));

        auth.register(new AuthController.RegisterRequest("Asha K", EMAIL, PASSWORD));

        verify(users).restartSignup(eq(1L), eq("Asha K"), anyString());
        verify(users, never()).create(any(), any(), any(), any(), eq(false));
    }

    @Test
    void registerRefusesAVerifiedEmail() {
        when(users.byEmail(EMAIL)).thenReturn(Optional.of(user(true)));

        assertThatThrownBy(() -> auth.register(new AuthController.RegisterRequest("Asha", EMAIL, PASSWORD)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("EMAIL_TAKEN"));
    }

    @Test
    void aNewCodeIsRefusedWithinAMinuteOfTheLastOne() {
        when(users.otp(EMAIL, "VERIFY")).thenReturn(Optional.of(otp("123456", 0, OffsetDateTime.now())));

        assertThatThrownBy(() -> auth.register(new AuthController.RegisterRequest("Asha", EMAIL, PASSWORD)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("WAIT"));
        verify(mailer, never()).sendOtp(any(), any(), any(), any());
    }

    // ---------- email verification ----------

    @Test
    void verifyWithTheRightCodeLogsTheUserIn() {
        when(users.byEmail(EMAIL)).thenReturn(Optional.of(user(false)));
        when(users.otp(EMAIL, "VERIFY")).thenReturn(Optional.of(otp("123456", 0, minutesAgo(2))));
        when(users.byId(1L)).thenReturn(Optional.of(user(true)));
        when(tokens.issue(any())).thenReturn("jwt");

        AuthController.Session session = auth.verify(new AuthController.VerifyRequest(EMAIL, "123456"));

        assertThat(session.token()).isEqualTo("jwt");
        assertThat(session.user().email()).isEqualTo(EMAIL);
        verify(users).markVerified(1L);
        verify(users).deleteOtp(EMAIL, "VERIFY");
    }

    @Test
    void verifyWithoutASignUpIsRefused() {
        assertThatThrownBy(() -> auth.verify(new AuthController.VerifyRequest(EMAIL, "123456")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void verifyWithoutACodeIsRefused() {
        when(users.byEmail(EMAIL)).thenReturn(Optional.of(user(false)));

        assertThatThrownBy(() -> auth.verify(new AuthController.VerifyRequest(EMAIL, "123456")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("BAD_REQUEST"));
    }

    @Test
    void anExpiredCodeIsRefused() {
        when(users.byEmail(EMAIL)).thenReturn(Optional.of(user(false)));
        when(users.otp(EMAIL, "VERIFY")).thenReturn(Optional.of(
                new Users.OtpRow(Otp.hash(EMAIL, "123456"), minutesAgo(1), 0, minutesAgo(11))));

        assertThatThrownBy(() -> auth.verify(new AuthController.VerifyRequest(EMAIL, "123456")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CODE_EXPIRED"));
    }

    @Test
    void aCodeIsLockedAfterFiveWrongAttempts() {
        when(users.byEmail(EMAIL)).thenReturn(Optional.of(user(false)));
        when(users.otp(EMAIL, "VERIFY")).thenReturn(Optional.of(otp("123456", 5, minutesAgo(2))));

        assertThatThrownBy(() -> auth.verify(new AuthController.VerifyRequest(EMAIL, "123456")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TOO_MANY_ATTEMPTS"));
    }

    @Test
    void aWrongCodeIsCountedAndRefused() {
        when(users.byEmail(EMAIL)).thenReturn(Optional.of(user(false)));
        when(users.otp(EMAIL, "VERIFY")).thenReturn(Optional.of(otp("123456", 0, minutesAgo(2))));

        assertThatThrownBy(() -> auth.verify(new AuthController.VerifyRequest(EMAIL, "654321")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CODE_WRONG"));
        verify(users).countAttempt(EMAIL, "VERIFY");
        verify(users, never()).markVerified(1L);
    }

    @Test
    void resendSendsANewCodeOnlyToAnUnverifiedAccount() {
        when(users.byEmail(EMAIL)).thenReturn(Optional.of(user(false)));
        auth.resend(new AuthController.EmailRequest(EMAIL));
        verify(mailer).sendOtp(eq(EMAIL), eq("Asha"), anyString(), eq("VERIFY"));

        when(users.byEmail("done@example.com")).thenReturn(Optional.of(
                new Users.User(2, "done@example.com", "Ravi", null, HASH, "CUSTOMER", true)));
        var response = auth.resend(new AuthController.EmailRequest("done@example.com"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(mailer, never()).sendOtp(eq("done@example.com"), any(), any(), any());
    }

    // ---------- login ----------

    @Test
    void loginWithTheRightPasswordReturnsAToken() {
        when(users.byEmail(EMAIL)).thenReturn(Optional.of(user(true)));
        when(tokens.issue(any())).thenReturn("jwt");

        AuthController.Session session = auth.login(new AuthController.LoginRequest(" ASHA@example.com", PASSWORD));

        assertThat(session.token()).isEqualTo("jwt");
        assertThat(session.user().name()).isEqualTo("Asha");
    }

    @Test
    void loginWithAWrongPasswordOrUnknownEmailIsRefused() {
        when(users.byEmail(EMAIL)).thenReturn(Optional.of(user(true)));

        assertThatThrownBy(() -> auth.login(new AuthController.LoginRequest(EMAIL, "wrong-password")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("BAD_CREDENTIALS"));
        assertThatThrownBy(() -> auth.login(new AuthController.LoginRequest("nobody@example.com", PASSWORD)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void loginBeforeVerificationSendsACodeAndAsksToVerify() {
        when(users.byEmail(EMAIL)).thenReturn(Optional.of(user(false)));

        assertThatThrownBy(() -> auth.login(new AuthController.LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("EMAIL_NOT_VERIFIED"));
        verify(mailer).sendOtp(eq(EMAIL), eq("Asha"), anyString(), eq("VERIFY"));
    }

    @Test
    void loginBeforeVerificationStillAsksToVerifyWhenACodeWasJustSent() {
        when(users.byEmail(EMAIL)).thenReturn(Optional.of(user(false)));
        when(users.otp(EMAIL, "VERIFY")).thenReturn(Optional.of(otp("123456", 0, OffsetDateTime.now())));

        assertThatThrownBy(() -> auth.login(new AuthController.LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("EMAIL_NOT_VERIFIED"));
        verify(mailer, never()).sendOtp(any(), any(), any(), any());
    }

    // ---------- password reset ----------

    @Test
    void forgotPasswordSendsAResetCodeOnlyForAVerifiedAccount() {
        when(users.byEmail(EMAIL)).thenReturn(Optional.of(user(true)));
        auth.forgot(new AuthController.EmailRequest(EMAIL));
        verify(mailer).sendOtp(eq(EMAIL), eq("Asha"), anyString(), eq("RESET"));

        var response = auth.forgot(new AuthController.EmailRequest("nobody@example.com"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(mailer, never()).sendOtp(eq("nobody@example.com"), any(), any(), any());
    }

    @Test
    void resetWithTheRightCodeChangesThePassword() {
        when(users.byEmail(EMAIL)).thenReturn(Optional.of(user(true)));
        when(users.otp(EMAIL, "RESET")).thenReturn(Optional.of(otp("123456", 0, minutesAgo(2))));
        when(tokens.issue(any())).thenReturn("jwt");

        AuthController.Session session = auth.reset(new AuthController.ResetRequest(EMAIL, "123456", "new-password"));

        assertThat(session.token()).isEqualTo("jwt");
        verify(users).updatePassword(eq(1L), anyString());
        verify(users).deleteOtp(EMAIL, "RESET");
    }

    @Test
    void resetForAnUnknownEmailIsRefused() {
        assertThatThrownBy(() -> auth.reset(new AuthController.ResetRequest(EMAIL, "123456", "new-password")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(users, never()).updatePassword(anyLong(), any());
    }

    // ---------- test data ----------

    private static Users.User user(boolean verified) {
        return new Users.User(1, EMAIL, "Asha", null, HASH, "CUSTOMER", verified);
    }

    private static Users.OtpRow otp(String code, int attempts, OffsetDateTime sentAt) {
        return new Users.OtpRow(Otp.hash(EMAIL, code), OffsetDateTime.now().plusMinutes(8), attempts, sentAt);
    }

    private static OffsetDateTime minutesAgo(long minutes) {
        return OffsetDateTime.now().minusMinutes(minutes);
    }
}
