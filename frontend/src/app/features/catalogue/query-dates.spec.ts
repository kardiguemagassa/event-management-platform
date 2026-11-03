import { fromQueryDate, startOfDay, toQueryDate } from './query-dates';

describe('query dates', () => {
  it('round-trips a local day', () => {
    const day = new Date(2026, 11, 3);
    expect(toQueryDate(day)).toBe('2026-12-03');
    expect(fromQueryDate('2026-12-03')).toEqual(day);
  });

  it('rejects absent, malformed and impossible days', () => {
    expect(fromQueryDate(undefined)).toBeNull();
    expect(fromQueryDate('03/12/2026')).toBeNull();
    expect(fromQueryDate('2026-02-30')).toBeNull();
  });

  it('starts a day at local midnight', () => {
    expect(startOfDay(new Date(2026, 10, 2, 14, 30))).toEqual(new Date(2026, 10, 2));
  });
});
