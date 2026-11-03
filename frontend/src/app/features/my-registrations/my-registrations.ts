import { EventResponse } from '../../api/events/models/event-response';
import { RegistrationResponse } from '../../api/registrations/models/registration-response';

/** A registration with the event it is for. */
export interface MyRegistration {
  registration: RegistrationResponse;
  event: EventResponse;
}

export interface SplitRegistrations {
  /** Earliest first: what comes next is on top. */
  upcoming: MyRegistration[];
  /** Latest first: the most recent memories on top. */
  past: MyRegistration[];
}

/**
 * Pairs each registration with its event and splits them around {@code now}. A registration whose event is not
 * returned (no longer visible to the caller) is left out rather than shown half empty.
 */
export function splitByDate(
  registrations: RegistrationResponse[],
  events: EventResponse[],
  now: Date,
): SplitRegistrations {
  const eventsById = new Map(events.map((event) => [event.id, event]));
  const entries = registrations.flatMap((registration) => {
    const event = eventsById.get(registration.eventId);
    return event ? [{ registration, event }] : [];
  });
  const startOf = (entry: MyRegistration) => new Date(entry.event.startAt ?? 0).getTime();
  return {
    upcoming: entries
      .filter((entry) => startOf(entry) >= now.getTime())
      .sort((a, b) => startOf(a) - startOf(b)),
    past: entries
      .filter((entry) => startOf(entry) < now.getTime())
      .sort((a, b) => startOf(b) - startOf(a)),
  };
}
