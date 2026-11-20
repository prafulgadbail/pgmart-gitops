package com.pgmart.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class MailerTest {

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final Mailer mailer = new Mailer(sender, "PGMart <no-reply@example.com>");

    @Test
    void verificationEmailCarriesTheCode() {
        mailer.sendOtp("asha@example.com", "Asha", "123456", "VERIFY");

        SimpleMailMessage mail = sent();
        assertThat(mail.getTo()).containsExactly("asha@example.com");
        assertThat(mail.getFrom()).isEqualTo("PGMart <no-reply@example.com>");
        assertThat(mail.getSubject()).isEqualTo("123456 is your PGMart verification code");
        assertThat(mail.getText()).contains("Hi Asha", "verification code is: 123456");
    }

    @Test
    void resetEmailSaysPasswordReset() {
        mailer.sendOtp("asha@example.com", "Asha", "654321", "RESET");

        SimpleMailMessage mail = sent();
        assertThat(mail.getSubject()).isEqualTo("654321 is your PGMart password reset code");
        assertThat(mail.getText()).contains("password reset code is: 654321");
    }

    @Test
    void aMailServerErrorBecomesAReadableError() {
        doThrow(new MailSendException("SMTP down")).when(sender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> mailer.sendOtp("asha@example.com", "Asha", "123456", "VERIFY"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("EMAIL_FAILED"));
    }

    private SimpleMailMessage sent() {
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(mail.capture());
        return mail.getValue();
    }
}
