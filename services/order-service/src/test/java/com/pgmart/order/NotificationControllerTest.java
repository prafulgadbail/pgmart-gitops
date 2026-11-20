package com.pgmart.order;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class NotificationControllerTest {

    private final Orders orders = mock(Orders.class);
    private final Auth auth = mock(Auth.class);
    private final NotificationController controller = new NotificationController(orders, auth);

    @Test
    void theBellWorksOnTheLoggedInUser() {
        when(auth.user("Bearer token")).thenReturn(new AuthUser(7, "asha@example.com", "Asha", "CUSTOMER"));

        controller.list("Bearer token");
        controller.markRead("Bearer token");
        controller.clear("Bearer token");

        verify(orders).notifications(7);
        verify(orders).markAllRead(7);
        verify(orders).clearNotifications(7);
    }
}
