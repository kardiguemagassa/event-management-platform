import { availability, seatsLeftLabel } from './seats';

describe('availability', () => {
  it('is open while more than a tenth of the seats are left', () => {
    expect(availability(200, 148)).toEqual({
      tone: 'open',
      label: 'Places disponibles',
      left: 52,
      booked: 74,
    });
  });

  it('is almost full from a tenth of the seats left', () => {
    expect(availability(30, 27)).toMatchObject({ tone: 'low', label: 'Presque complet', left: 3 });
  });

  it('is full without seat left, never below zero', () => {
    expect(availability(80, 80)).toMatchObject({
      tone: 'full',
      label: 'Complet',
      left: 0,
      booked: 100,
    });
    expect(availability(2, 3)).toMatchObject({ tone: 'full', left: 0, booked: 100 });
  });

  it('treats an unknown capacity as full', () => {
    expect(availability(0, 0)).toMatchObject({ tone: 'full', booked: 100 });
  });
});

describe('seatsLeftLabel', () => {
  it('agrees in number', () => {
    expect(seatsLeftLabel(0)).toBe('Complet');
    expect(seatsLeftLabel(1)).toBe('1 place restante');
    expect(seatsLeftLabel(12)).toBe('12 places restantes');
  });
});
