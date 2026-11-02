import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Remote } from './remote';

/** Every list goes through this: loading, the API's error message, an explicit empty state, or the content. */
@Component({
  selector: 'app-state',
  imports: [MatProgressBarModule, MatIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (r().loading()) {
      <mat-progress-bar class="state-bar" mode="indeterminate" />
    }
    @if (r().error(); as e) {
      <div class="state-error" role="alert" data-testid="state-error">
        <mat-icon>error_outline</mat-icon>
        <div>
          <strong>{{ e.message }}</strong>
          @if (e.hint) { <span class="muted"> {{ e.hint }}.</span> }
          <code class="muted"> {{ e.code }}</code>
        </div>
      </div>
    } @else if (r().data() === null) {
      <div class="state-msg" data-testid="state-loading">{{ r().loading() ? 'Loading…' : '' }}</div>
    } @else if (empty()) {
      <div class="state-msg" data-testid="state-empty">{{ emptyText() }}</div>
    } @else {
      <ng-content />
    }
  `,
})
export class StateComponent {
  readonly r = input.required<Remote<any>>();
  readonly empty = input(false);
  readonly emptyText = input('Nothing here.');
}
