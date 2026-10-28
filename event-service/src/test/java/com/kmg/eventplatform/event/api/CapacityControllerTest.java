package com.kmg.eventplatform.event.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kmg.eventplatform.event.application.CapacityOutcome;
import com.kmg.eventplatform.event.application.CapacityService;
import com.kmg.eventplatform.event.application.EventNotFoundException;
import com.kmg.eventplatform.event.domain.EventFullException;
import com.kmg.eventplatform.event.domain.EventNotRegistrableException;
import com.kmg.eventplatform.event.domain.EventStatus;
import com.kmg.eventplatform.event.domain.ReservationConflictException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(CapacityController.class)
class CapacityControllerTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID RESERVATION = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CapacityService service;

    private ResultActions call(String action, String body) throws Exception {
        return mockMvc.perform(post("/api/events/{id}/capacity/{action}", EVENT, action)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions call(String action) throws Exception {
        return call(action, "{\"reservationId\": \"" + RESERVATION + "\"}");
    }

    @Test
    void reserveReturnsOutcome() throws Exception {
        when(service.reserve(EVENT, RESERVATION)).thenReturn(CapacityOutcome.RESERVED);

        call("reserve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(EVENT.toString()))
                .andExpect(jsonPath("$.reservationId").value(RESERVATION.toString()))
                .andExpect(jsonPath("$.outcome").value("RESERVED"));
    }

    @Test
    void replayedReserveIsStillA200() throws Exception {
        when(service.reserve(EVENT, RESERVATION)).thenReturn(CapacityOutcome.ALREADY_RESERVED);

        call("reserve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_RESERVED"));
    }

    @Test
    void reserveRequiresAReservationId() throws Exception {
        call("reserve", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.reservationId").exists());

        verify(service, never()).reserve(any(), any());
    }

    @Test
    void fullEventReturns409EventFull() throws Exception {
        when(service.reserve(EVENT, RESERVATION)).thenThrow(new EventFullException(EVENT));

        call("reserve")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_FULL"))
                .andExpect(jsonPath("$.eventId").value(EVENT.toString()));
    }

    @Test
    void notRegistrableEventReturns409() throws Exception {
        when(service.reserve(EVENT, RESERVATION))
                .thenThrow(new EventNotRegistrableException(EVENT, EventStatus.CANCELLED));

        call("reserve")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_REGISTRABLE"));
    }

    @Test
    void reservationIdReusedForAnotherEventReturns409() throws Exception {
        when(service.reserve(EVENT, RESERVATION)).thenThrow(new ReservationConflictException(EVENT, RESERVATION));

        call("reserve")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_ID_CONFLICT"));
    }

    @Test
    void unknownEventReturns404() throws Exception {
        when(service.reserve(EVENT, RESERVATION)).thenThrow(new EventNotFoundException(EVENT));

        call("reserve")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    @Test
    void releaseReturnsOutcome() throws Exception {
        when(service.release(EVENT, RESERVATION)).thenReturn(CapacityOutcome.RELEASED);

        call("release")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RELEASED"));
    }
}
