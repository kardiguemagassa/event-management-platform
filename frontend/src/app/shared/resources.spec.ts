import { ApplicationRef, resource } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { valueOf } from './resources';

describe('valueOf', () => {
  it('gives the value once loaded, nothing while failed', async () => {
    const loaded = TestBed.runInInjectionContext(() => resource({ loader: async () => 42 }));
    const failed = TestBed.runInInjectionContext(() =>
      resource<number, unknown>({
        loader: async () => {
          throw new Error('down');
        },
      }),
    );
    await TestBed.inject(ApplicationRef).whenStable();

    expect(valueOf(loaded)).toBe(42);
    expect(() => failed.value()).toThrow();
    expect(valueOf(failed)).toBeUndefined();
  });
});
