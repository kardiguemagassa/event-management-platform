import { ChangeDetectionStrategy, Component, inject, Injectable } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';

export interface Confirmation {
  title: string;
  message: string;
  /** Label of the confirming button, saying what it does ("Annuler l'événement", not "OK"). */
  confirm: string;
  /** Red confirming button for destructive actions. */
  danger?: boolean;
}

/** Accessible confirmation (focus trapped, Escape closes, the safe choice is the default). */
@Component({
  selector: 'app-confirm-dialog',
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button [mat-dialog-close]="false" cdkFocusInitial>Retour</button>
      <button mat-flat-button [class.danger]="data.danger" [mat-dialog-close]="true">
        {{ data.confirm }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    p {
      margin: 0;
      line-height: 1.5;
    }
    .danger {
      --mat-button-filled-container-color: #b91c1c;
      --mat-button-filled-label-text-color: #fff;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmDialog {
  protected readonly data = inject<Confirmation>(MAT_DIALOG_DATA);
}

@Injectable({ providedIn: 'root' })
export class Confirmations {
  private readonly dialog = inject(MatDialog);

  /** True only when the user confirmed (Retour, Escape and a click outside all mean no). */
  async ask(confirmation: Confirmation): Promise<boolean> {
    const ref = this.dialog.open<ConfirmDialog, Confirmation, boolean>(ConfirmDialog, {
      data: confirmation,
      width: '440px',
      maxWidth: 'calc(100vw - 32px)',
    });
    return (await firstValueFrom(ref.afterClosed())) === true;
  }
}
