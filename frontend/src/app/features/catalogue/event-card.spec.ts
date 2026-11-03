import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { EventResponse } from '../../api/events/models/event-response';
import { provideIcons } from '../../core/ui/icons';
import { EventCard } from './event-card';

registerLocaleData(localeFr);

describe('EventCard', () => {
  function render(event: EventResponse): HTMLElement {
    TestBed.configureTestingModule({
      providers: [{ provide: LOCALE_ID, useValue: 'fr-FR' }, provideIcons()],
    });
    const fixture = TestBed.createComponent(EventCard);
    fixture.componentRef.setInput('event', event);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the date in French, the place and the seats left', () => {
    const card = render({
      title: 'Spring Boot Day',
      location: 'Paris, La Défense',
      startAt: new Date(2026, 11, 3, 9, 0).toISOString(),
      capacity: 200,
      reservedSeats: 148,
    });

    expect(card.querySelector('h2')?.textContent).toBe('Spring Boot Day');
    expect(card.querySelector('time')?.textContent?.trim()).toBe('jeu. 3 déc. · 09:00');
    expect(card.querySelector('.place')?.textContent).toContain('Paris, La Défense');
    expect(card.querySelector('.badge')?.textContent).toBe('Places disponibles');
    expect(card.querySelector('.seats')?.textContent).toBe('52 places restantes');
    expect(card.querySelector('[role="meter"]')?.getAttribute('aria-valuenow')).toBe('74');
    expect(card.querySelector('.cta')?.textContent?.trim()).toBe("Voir et s'inscrire");
  });

  it('flags a full event', () => {
    const card = render({ title: 'Kafka en pratique', capacity: 80, reservedSeats: 80 });

    expect(card.querySelector('.badge')?.classList).toContain('full');
    expect(card.querySelector('.seats')?.textContent).toBe('Complet');
    expect(card.querySelector('.cta')?.textContent?.trim()).toBe('Voir le détail');
  });
});
