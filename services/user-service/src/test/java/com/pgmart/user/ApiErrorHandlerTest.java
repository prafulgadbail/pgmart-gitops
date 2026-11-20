package com.pgmart.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class ApiErrorHandlerTest {

    private final ApiErrorHandler handler = new ApiErrorHandler();

    @Test
    void anApiErrorKeepsItsStatusCodeAndMessage() {
        var response = handler.api(ApiException.conflict("EMAIL_TAKEN", "Already registered"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "EMAIL_TAKEN").containsEntry("message", "Already registered");
    }

    @Test
    void theOtherApiErrorsHaveTheirOwnStatus() {
        assertThat(ApiException.notFound("x").status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ApiException.forbidden().status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ApiException.unauthorized().status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aValidationErrorNamesTheField() {
        BindingResult result = mock(BindingResult.class);
        when(result.getFieldError()).thenReturn(new FieldError("req", "email", "must be a well-formed email address"));
        MethodArgumentNotValidException error = mock(MethodArgumentNotValidException.class);
        when(error.getBindingResult()).thenReturn(result);

        var response = handler.invalid(error);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "email: must be a well-formed email address");
    }

    @Test
    void aValidationErrorWithoutAFieldHasAGeneralMessage() {
        MethodArgumentNotValidException error = mock(MethodArgumentNotValidException.class);
        when(error.getBindingResult()).thenReturn(mock(BindingResult.class));

        assertThat(handler.invalid(error).getBody()).containsEntry("message", "Invalid request");
    }

    @Test
    void unreadableJsonUnknownPathAndUnexpectedErrors() {
        assertThat(handler.unreadable(mock(HttpMessageNotReadableException.class)).getBody())
                .containsEntry("code", "BAD_REQUEST");
        assertThat(handler.noRoute(mock(NoResourceFoundException.class)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        var response = handler.unexpected(new IllegalStateException("boom"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("code", "INTERNAL");
    }
}
