import { fullNameControl, fullNameError } from './full-name';

describe('full name', () => {
  it('requires something else than spaces, 100 characters at most', () => {
    const control = fullNameControl('  ');
    expect(fullNameError(control)).toBe('Indique ton nom.');

    control.setValue('x'.repeat(101));
    expect(fullNameError(control)).toBe('100 caractères au maximum.');

    control.setValue('Ada Lovelace');
    expect(control.valid).toBe(true);
    expect(fullNameError(control)).toBeNull();
  });
});
