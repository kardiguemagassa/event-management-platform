import { EventResponse } from '../../api/events/models/event-response';
import { RegistrationResponse } from '../../api/registrations/models/registration-response';
import { splitByDate } from './my-registrations';

const NOW = new Date('2026-11-03T10:00:00Z');
const event = (id: string, startAt: string): EventResponse => ({ id, title: id, startAt });
const registration = (id: string, eventId: string): RegistrationResponse => ({
  id,
  eventId,
  status: 'CONFIRMED',
});

describe('splitByDate', () => {
  it('puts the next events first and the latest past events first', () => {
    const split = splitByDate(
      [
        registration('r1', 'later'),
        registration('r2', 'past'),
        registration('r3', 'soon'),
        registration('r4', 'old'),
      ],
      [
        event('later', '2026-12-20T09:00:00Z'),
        event('soon', '2026-11-04T09:00:00Z'),
        event('past', '2026-10-30T09:00:00Z'),
        event('old', '2026-01-10T09:00:00Z'),
      ],
      NOW,
    );

    expect(split.upcoming.map((entry) => entry.event.id)).toEqual(['soon', 'later']);
    expect(split.past.map((entry) => entry.event.id)).toEqual(['past', 'old']);
  });

  it('counts an event starting now as upcoming', () => {
    const split = splitByDate([registration('r1', 'now')], [event('now', NOW.toISOString())], NOW);
    expect(split.upcoming).toHaveLength(1);
  });

  it('leaves out a registration whose event is not visible', () => {
    const split = splitByDate([registration('r1', 'gone')], [], NOW);
    expect(split.upcoming).toEqual([]);
    expect(split.past).toEqual([]);
  });
});
