import { eventForm, fieldError, fill, fromLocalInput, toLocalInput, toRequest } from './event-form';

describe('event form', () => {
  const tomorrow = () => toLocalInput(new Date(Date.now() + 86_400_000).toISOString());

  it('accepts a complete event and sends it trimmed, start as an instant', () => {
    const form = eventForm();
    const start = tomorrow();
    form.setValue({
      title: '  Meetup Angular ',
      location: ' Nantes ',
      startAt: start,
      capacity: 60,
      description: '   ',
    });

    expect(form.valid).toBe(true);
    expect(toRequest(form)).toEqual({
      title: 'Meetup Angular',
      location: 'Nantes',
      startAt: new Date(start).toISOString(),
      capacity: 60,
      description: undefined,
    });
  });

  it('says in French what is wrong with each field', () => {
    const form = eventForm();
    form.setValue({
      title: ' ',
      location: 'x'.repeat(201),
      startAt: '2000-01-01T10:00',
      capacity: 100_001,
      description: '',
    });

    expect(fieldError(form.controls.title)).toBe('Ce champ est obligatoire.');
    expect(fieldError(form.controls.location)).toBe('200 caractères au maximum.');
    expect(fieldError(form.controls.startAt)).toBe('La date de début doit être dans le futur.');
    expect(fieldError(form.controls.capacity)).toBe('100 000 places au maximum.');
    form.controls.capacity.setValue(0);
    expect(fieldError(form.controls.capacity)).toBe('Au moins 1 place.');
    expect(fieldError(form.controls.description)).toBeNull();
    form.controls.description.setErrors({ server: 'Valeur refusée.' });
    expect(fieldError(form.controls.description)).toBe('Valeur refusée.');
  });

  it('fills an event to edit, never below the seats already reserved', () => {
    const form = eventForm();
    const startAt = new Date(Date.now() + 86_400_000).toISOString();
    fill(form, { title: 'Day', location: 'Paris', startAt, capacity: 200, reservedSeats: 148 });

    expect(form.controls.startAt.value).toBe(toLocalInput(startAt));
    expect(form.pristine).toBe(true);
    form.controls.capacity.setValue(100);
    expect(fieldError(form.controls.capacity, 148)).toBe(
      'Au moins 148 places : 148 sont déjà réservées.',
    );
  });

  it('reads and writes the value of a date-time input in the local time zone', () => {
    const local = toLocalInput(new Date(2099, 0, 21, 14, 5).toISOString());
    expect(local).toBe('2099-01-21T14:05');
    expect(fromLocalInput(local)).toBe(new Date(2099, 0, 21, 14, 5).toISOString());
    expect(fromLocalInput('pas une date')).toBeNull();
  });
});
