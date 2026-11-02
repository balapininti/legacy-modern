import { ChangeDetectionStrategy, Component, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatStepper, MatStepperModule } from '@angular/material/stepper';
import { Api, DoctorResult, toApiError } from '../core/api';
import { Remote } from '../core/remote';
import { StateComponent } from '../core/state.component';

@Component({
  selector: 'app-setup',
  imports: [FormsModule, MatButtonModule, MatStepperModule, StateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    textarea { width: 100%; box-sizing: border-box; height: calc(100vh - 300px); min-height: 200px; font-family: var(--mono); font-size: 12px; }
    .buttons { display: flex; gap: 8px; margin-top: 8px; align-items: center; }
  `,
  template: `
    <h1>Setup wizard</h1>
    <app-state [r]="config">
      @if (config.data(); as c) {
        <mat-stepper #stepper animationDuration="0">
          <mat-step label="Edit toolkit.yaml">
            <p class="muted mono">{{ c.path }}</p>
            <textarea aria-label="toolkit.yaml" spellcheck="false" data-testid="yaml" [ngModel]="yaml()" (ngModelChange)="edit($event)"></textarea>
            <div class="buttons">
              <button matButton="filled" data-testid="run-doctor" (click)="runDoctor()">Run doctor</button>
              <button matButton matStepperNext>Next</button>
            </div>
          </mat-step>
          <mat-step label="Check it">
            <p class="muted">Doctor tests what you typed, without saving it: source root, database, LLM endpoint, z/OS access and the identity provider.</p>
            <div class="buttons"><button matButton="filled" (click)="runDoctor()">Run doctor again</button></div>
            <app-state [r]="doctor" emptyText="">
              @if (doctor.data(); as d) {
                <table class="grid" data-testid="doctor">
                  <thead><tr><th>Check</th><th>Result</th><th>Detail</th></tr></thead>
                  <tbody>
                    @for (x of d.checks; track $index) {
                      <tr [attr.data-check]="x.check">
                        <td>{{ x.check }}</td>
                        <td><span class="badge" [class.good]="x.status === 'PASS'" [class.bad]="x.status === 'FAIL'">{{ x.status }}</span></td>
                        <td class="mono">{{ x.detail }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              }
            </app-state>
            <div class="buttons">
              <button matButton matStepperPrevious>Back</button>
              <button matButton matStepperNext data-testid="to-save">Next</button>
            </div>
          </mat-step>
          <mat-step label="Save">
            @if (warning(); as w) { <p class="state-error" role="status" data-testid="save-warning">{{ w }}</p> }
            @if (saveError(); as e) { <p class="state-error" role="alert" data-testid="save-error">{{ e }}</p> }
            @if (saved(); as s) { <p class="badge good" role="status" data-testid="saved">Saved to {{ s }}</p> }
            <div class="buttons">
              <button matButton matStepperPrevious>Back</button>
              <button matButton="filled" data-testid="save" (click)="save()">Save</button>
            </div>
          </mat-step>
        </mat-stepper>
      }
    </app-state>
  `,
})
export class SetupPage {
  private readonly api = inject(Api);
  private readonly stepper = viewChild<MatStepper>('stepper');
  readonly config = new Remote<{ path: string; yaml: string }>();
  readonly doctor = new Remote<DoctorResult>();
  readonly yaml = signal('');
  readonly checkedYaml = signal<string | null>(null);
  readonly saved = signal<string | null>(null);
  readonly saveError = signal<string | null>(null);
  readonly warning = computed(() => {
    if (this.checkedYaml() !== this.yaml()) return 'Doctor has not checked this text.';
    const failed = this.doctor.data()?.checks.filter((c) => c.status === 'FAIL') ?? [];
    return failed.length ? `Doctor reports ${failed.length} failing check(s): ${failed.map((c) => c.check).join(', ')}.` : null;
  });

  constructor() {
    void this.config.load(async () => {
      const c = await this.api.getConfig();
      this.yaml.set(c.yaml);
      return c;
    });
  }

  edit(v: string): void {
    this.yaml.set(v);
    this.saved.set(null);
    this.saveError.set(null);
  }

  runDoctor(): void {
    const text = this.yaml();
    const s = this.stepper();
    if (s) s.selectedIndex = 1;
    this.checkedYaml.set(text);
    void this.doctor.load(() => this.api.doctor(text));
  }

  async save(): Promise<void> {
    this.saved.set(null);
    this.saveError.set(null);
    try {
      this.saved.set((await this.api.saveConfig(this.yaml())).saved);
    } catch (e) {
      const a = toApiError(e);
      this.saveError.set(`${a.message}${a.hint ? `: ${a.hint}` : ''}`);
    }
  }
}
