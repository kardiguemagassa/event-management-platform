import { TestBed } from '@angular/core/testing';
import { Confirmations } from './confirm-dialog';

describe('Confirmations', () => {
  const buttons = () => [
    ...document.querySelectorAll<HTMLButtonElement>('mat-dialog-actions button'),
  ];

  async function closeWith(label: string, answer: Promise<boolean>): Promise<boolean> {
    await new Promise((resolve) => setTimeout(resolve));
    expect(document.querySelector('[mat-dialog-title]')?.textContent).toBe(
      'Supprimer « Meetup » ?',
    );
    buttons()
      .find((button) => button.textContent?.trim() === label)!
      .click();
    return answer;
  }

  const ask = () =>
    TestBed.inject(Confirmations).ask({
      title: 'Supprimer « Meetup » ?',
      message: "L'événement est effacé définitivement.",
      confirm: 'Supprimer',
      danger: true,
    });

  it('says yes only when the confirming button is chosen', async () => {
    expect(await closeWith('Supprimer', ask())).toBe(true);
    expect(await closeWith('Retour', ask())).toBe(false);
  });
});
