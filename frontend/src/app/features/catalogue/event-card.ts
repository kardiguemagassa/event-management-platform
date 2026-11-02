import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { EventResponse } from '../../api/events/models/event-response';
import { availability, seatsLeftLabel } from '../../shared/seats';

/** One event of the catalogue: date, availability badge, title, place and seat gauge. */
@Component({
  selector: 'app-event-card',
  imports: [DatePipe, MatIconModule],
  templateUrl: './event-card.html',
  styleUrl: './event-card.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EventCard {
  readonly event = input.required<EventResponse>();

  protected readonly seats = computed(() => {
    const event = this.event();
    return availability(event.capacity ?? 0, event.reservedSeats ?? 0);
  });
  protected readonly seatsLabel = computed(() => seatsLeftLabel(this.seats().left));
}
