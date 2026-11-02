/** How full an event is, as the badge and the gauge of the mockups show it. */
export interface Availability {
  tone: 'open' | 'low' | 'full';
  label: string;
  /** Seats left, never negative. */
  left: number;
  /** Booked share of the capacity, 0 to 100 (gauge width). */
  booked: number;
}

/** "Almost full" from this share of seats left. */
const LOW_SHARE = 0.1;

export function availability(capacity: number, reserved: number): Availability {
  const left = Math.max(capacity - reserved, 0);
  const booked = capacity > 0 ? Math.min(Math.round((reserved / capacity) * 100), 100) : 100;
  if (left === 0) {
    return { tone: 'full', label: 'Complet', left, booked };
  }
  if (left / capacity <= LOW_SHARE) {
    return { tone: 'low', label: 'Presque complet', left, booked };
  }
  return { tone: 'open', label: 'Places disponibles', left, booked };
}

/** "1 place restante", "12 places restantes", "Complet". */
export function seatsLeftLabel(left: number): string {
  if (left === 0) {
    return 'Complet';
  }
  return left === 1 ? '1 place restante' : `${left} places restantes`;
}
