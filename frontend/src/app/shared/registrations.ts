import { RegistrationResponse } from '../api/registrations/models/registration-response';

/**
 * Holds, or is about to hold, a seat: waiting for event-service's answer (PENDING) or confirmed. At most one per
 * attendee and event.
 */
export function isActive(registration: RegistrationResponse): boolean {
  return registration.status === 'PENDING' || registration.status === 'CONFIRMED';
}
