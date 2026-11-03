import { CanDeactivateFn } from '@angular/router';

/** A page that may hold work not saved yet, and asks before it is lost. */
export interface LeaveCheck {
  canLeave(): boolean | Promise<boolean>;
}

export const confirmLeave: CanDeactivateFn<LeaveCheck> = (page) => page.canLeave();
