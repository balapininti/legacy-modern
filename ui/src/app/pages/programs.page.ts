import { CdkVirtualScrollViewport, ScrollingModule } from '@angular/cdk/scrolling';
import { ChangeDetectionStrategy, Component, Injector, afterNextRender, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTabsModule } from '@angular/material/tabs';
import { Router, RouterLink } from '@angular/router';
import { Api, ProgramDetail, ProgramRow, parseJson } from '../core/api';
import { FieldsComponent, RulesComponent } from '../core/content.components';
import { Remote } from '../core/remote';
import { StateComponent } from '../core/state.component';

const LINE_PX = 18;

@Component({
  selector: 'app-programs',
  imports: [FormsModule, RouterLink, ScrollingModule, MatFormFieldModule, MatInputModule, MatTabsModule, MatButtonModule, StateComponent, RulesComponent, FieldsComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: grid; grid-template-columns: 170px minmax(0, 1fr) 440px; gap: 12px; height: 100%; min-height: 0; }
    .col { display: flex; flex-direction: column; min-height: 0; min-width: 0; height: calc(100vh - 68px); }
    .col > app-state { display: flex; flex-direction: column; flex: 1; min-height: 0; }
    .full { width: 100%; }
    .list { flex: 1; overflow: auto; border: 1px solid var(--line); margin-top: 6px; }
    .list .selected { background: #d3e3fd !important; font-weight: 600; }
    .bar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 6px; }
    .bar mat-form-field { width: 96px; }
    .bar .find { width: 150px; }
    .source { flex: 1; border: 1px solid var(--line); font-family: var(--mono); font-size: 12px; background: #fbfbfc; }
    .src-line { display: flex; height: ${LINE_PX}px; line-height: ${LINE_PX}px; white-space: pre; }
    .src-line.hit { background: #fff3c4; }
    .src-line.target { background: #ffe08a; }
    .ln { flex: none; width: 52px; padding-right: 10px; text-align: right; color: var(--muted); background: #eef1f5; user-select: none; position: sticky; left: 0; }
    .tx { padding-left: 8px; }
    .side { flex: 1; min-height: 0; border: 1px solid var(--line); }
    .pane { padding: 8px; }
    .dv { height: calc(100vh - 210px); font-size: 12px; }
    .dv-row { display: grid; grid-template-columns: 26px minmax(0, 1fr) 90px 60px 42px 42px; gap: 6px; height: 20px; line-height: 20px; padding: 0 4px; white-space: nowrap; overflow: hidden; }
    .dv-row > * { overflow: hidden; text-overflow: ellipsis; }
    .dv-row.head { font-weight: 600; background: #eef1f5; }
    .dv-row .r { text-align: right; }
  `,
  template: `
    <section class="col">
      <mat-form-field class="full">
        <mat-label>Search programs</mat-label>
        <input matInput data-testid="program-search" [ngModel]="q()" (ngModelChange)="search($event)" />
      </mat-form-field>
      <div class="list">
        <app-state [r]="list" [empty]="list.data()?.length === 0" emptyText="No program matches.">
          <table class="grid" data-testid="program-list">
            <thead><tr><th>Program</th><th>Kind</th></tr></thead>
            <tbody>
              @for (p of list.data(); track p.program_id) {
                <tr [class.selected]="p.program_id === prog.data()?.program_id">
                  <td><a [routerLink]="['/programs', p.program_id]">{{ p.program_id }}</a></td>
                  <td class="muted">{{ p.kind }}</td>
                </tr>
              }
            </tbody>
          </table>
        </app-state>
      </div>
    </section>

    @if (name()) {
      <section class="col">
        <app-state [r]="prog" emptyText="">
          @if (prog.data(); as p) {
            <div class="bar">
              <h1 style="margin:0" data-testid="program-title">{{ p.program_id }}</h1>
              <span class="badge">{{ p.kind }}</span>
              <span class="badge" data-testid="program-stage">{{ p.stage }}</span>
              <span class="muted mono">{{ p.member }} · {{ p.source.length }} lines</span>
              <span class="spacer"></span>
              <mat-form-field>
                <mat-label>Go to line</mat-label>
                <input matInput type="number" min="1" data-testid="goto-line" [ngModel]="gotoText()" (ngModelChange)="gotoText.set($event)" (keydown.enter)="goTo(+gotoText())" />
              </mat-form-field>
              <mat-form-field class="find">
                <mat-label>Find in source</mat-label>
                <input matInput data-testid="find" [ngModel]="find()" (ngModelChange)="find.set($event)" (keydown.enter)="nextHit()" />
              </mat-form-field>
              @if (find()) {
                <span class="muted nowrap">{{ hits().length }} found</span>
                <button matButton (click)="nextHit()" [disabled]="!hits().length">Next</button>
              }
            </div>
            <!-- Source is untrusted data: interpolation binds it as text. -->
            <cdk-virtual-scroll-viewport class="source" itemSize="${LINE_PX}" minBufferPx="360" maxBufferPx="720" data-testid="source">
              <div *cdkVirtualFor="let line of p.source; let i = index; templateCacheSize: 0" class="src-line" [class.hit]="hitSet().has(i + 1)" [class.target]="target() === i + 1" [attr.data-line]="i + 1">
                <span class="ln">{{ i + 1 }}</span><span class="tx">{{ line }}</span>
              </div>
            </cdk-virtual-scroll-viewport>
          }
        </app-state>
      </section>

      <section class="col">
        @if (prog.data(); as p) {
          <mat-tab-group class="side" animationDuration="0" data-testid="side-panel" [mat-stretch-tabs]="false">
            <mat-tab label="Summary">
              <ng-template matTabContent>
                <div class="pane" data-testid="panel-summary">
                  @for (a of summaries(); track a.id) {
                    <h2>Summary <span class="badge">{{ a.status }}</span></h2>
                    <app-fields [content]="a.content" />
                  } @empty { <p class="state-msg">No summary yet: the knowledge pass has not covered this program.</p> }
                  @for (a of notes(); track a.id) {
                    <h2>Migration notes <span class="badge">{{ a.status }}</span></h2>
                    <app-fields [content]="a.content" />
                  }
                  <h2>Metrics</h2>
                  <app-fields [content]="p.metrics" />
                </div>
              </ng-template>
            </mat-tab>
            <mat-tab [label]="'Rules ' + ruleCount()">
              <ng-template matTabContent>
                <div class="pane" data-testid="panel-rules">
                  @for (a of rules(); track a.id) {
                    <p><span class="badge">{{ a.status }}</span> @if (a.reviewer) { <span class="muted">by {{ a.reviewer }}</span> }</p>
                    <app-rules [content]="a.content" [compact]="true" (lineClick)="goTo($event)" />
                  } @empty { <p class="state-msg">No rules extracted for this program yet.</p> }
                </div>
              </ng-template>
            </mat-tab>
            <mat-tab [label]="'Data ' + p.data_items.length">
              <ng-template matTabContent>
                <div class="pane">
                  <mat-form-field style="width:100%">
                    <mat-label>Filter data items</mat-label>
                    <input matInput [ngModel]="itemFilter()" (ngModelChange)="itemFilter.set($event)" />
                  </mat-form-field>
                  @if (!items().length) { <p class="state-msg">No data items.</p> } @else {
                    <div class="dv-row head"><span>Lv</span><span>Name</span><span>PIC</span><span>Usage</span><span class="r">Off</span><span class="r">Len</span></div>
                    <cdk-virtual-scroll-viewport class="dv" itemSize="20" data-testid="panel-data">
                      <div *cdkVirtualFor="let d of items()" class="dv-row">
                        <span>{{ d.level }}</span>
                        <a [routerLink]="['/graph']" [queryParams]="{ view: 'lineage', item: d.name, program: p.program_id }" title="Field lineage of {{ d.name }}">{{ d.name }}</a>
                        <span class="mono" [title]="d.pic ?? ''">{{ d.pic }}</span><span>{{ d.usage }}</span><span class="r">{{ d.offset }}</span><span class="r">{{ d.length }}</span>
                      </div>
                    </cdk-virtual-scroll-viewport>
                  }
                </div>
              </ng-template>
            </mat-tab>
            <mat-tab [label]="'Calls ' + p.callers.length + '/' + p.callees.length">
              <ng-template matTabContent>
                <div class="pane">
                  <h2>Callers</h2>
                  @if (!p.callers.length) { <p class="state-msg">Nothing calls this program.</p> } @else {
                    <table class="grid" data-testid="callers">
                      <thead><tr><th>Caller</th><th>How</th><th class="num">Line</th></tr></thead>
                      <tbody>
                        @for (c of p.callers; track $index) {
                          <tr>
                            <td><button class="linklike" (click)="openCaller(c.from_program, c.line, c.edge_type)">{{ c.from_program }}</button></td>
                            <td>{{ c.edge_type }}</td><td class="num">{{ c.line }}</td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  }
                  <h2>Callees</h2>
                  @if (!p.callees.length) { <p class="state-msg">This program calls nothing.</p> } @else {
                    <table class="grid" data-testid="callees">
                      <thead><tr><th>Callee</th><th>How</th><th class="num">Line</th></tr></thead>
                      <tbody>
                        @for (c of p.callees; track $index) {
                          <tr>
                            <td><a [routerLink]="['/programs', c.to_program]">{{ c.to_program }}</a></td>
                            <td>{{ c.edge_type }}</td>
                            <td class="num">@if (c.line) { <button class="linklike" (click)="goTo(c.line)">{{ c.line }}</button> }</td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  }
                  <p><a [routerLink]="['/graph']" [queryParams]="{ view: 'calls', program: p.program_id }">Open call graph</a></p>
                </div>
              </ng-template>
            </mat-tab>
            <mat-tab [label]="'Paragraphs ' + p.paragraphs.length">
              <ng-template matTabContent>
                <div class="pane">
                  @if (!p.paragraphs.length) { <p class="state-msg">No paragraphs.</p> } @else {
                    <table class="grid">
                      <thead><tr><th>Paragraph</th><th class="num">Lines</th><th class="num">Cx</th><th>Reachable</th></tr></thead>
                      <tbody>
                        @for (x of p.paragraphs; track $index) {
                          <tr>
                            <td class="mono">{{ x.name }}</td>
                            <td class="num"><button class="linklike" (click)="goTo(x.line_start)">{{ x.line_start }}-{{ x.line_end }}</button></td>
                            <td class="num">{{ x.complexity }}</td>
                            <td>{{ x.reachable ? 'yes' : 'NO' }}</td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  }
                </div>
              </ng-template>
            </mat-tab>
          </mat-tab-group>
        }
      </section>
    } @else {
      <section class="col" style="grid-column: 2 / 4"><p class="state-msg" data-testid="pick-program">Pick a program from the list.</p></section>
    }
  `,
})
export class ProgramsPage {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);

  /** Route parameter and ?line=, bound by the router. */
  readonly name = input<string>();
  readonly line = input<string>();

  readonly q = signal('');
  readonly list = new Remote<ProgramRow[]>();
  readonly prog = new Remote<ProgramDetail>();
  readonly target = signal<number | null>(null);
  readonly gotoText = signal('');
  readonly find = signal('');
  readonly itemFilter = signal('');
  private hitCursor = -1;
  private scrollRequest = 0;

  private readonly viewport = viewChild(CdkVirtualScrollViewport);

  private artifacts = (kind: string) => (this.prog.data()?.artifacts ?? []).filter((a) => a.kind === kind);
  readonly summaries = computed(() => this.artifacts('summary'));
  readonly notes = computed(() => this.artifacts('migration_note'));
  readonly rules = computed(() => this.artifacts('rule'));
  readonly ruleCount = computed(() => this.rules().reduce((n, a) => n + (parseJson<{ rules?: unknown[] }>(a.content, {}).rules?.length ?? 0), 0));
  readonly items = computed(() => {
    const all = this.prog.data()?.data_items ?? [];
    const f = this.itemFilter().trim().toUpperCase();
    return f ? all.filter((d) => d.name.toUpperCase().includes(f)) : all;
  });
  readonly hits = computed(() => {
    const f = this.find().trim().toUpperCase();
    const src = this.prog.data()?.source ?? [];
    if (!f) return [];
    const out: number[] = [];
    src.forEach((l, i) => {
      if (l.toUpperCase().includes(f)) out.push(i + 1);
    });
    return out;
  });
  readonly hitSet = computed(() => new Set(this.hits()));

  constructor() {
    void this.list.load(() => this.api.programs());
    effect(() => {
      const n = this.name();
      this.prog.data.set(null);
      this.find.set('');
      this.itemFilter.set('');
      this.target.set(null);
      this.gotoText.set('');
      if (n) void this.prog.load(() => this.api.program(n));
    });
    // Once the source is on the page, honour ?line=.
    effect(() => {
      const l = Number(this.line());
      if (this.prog.data() && l > 0) afterNextRender(() => this.goTo(l), { injector: this.injector });
    });
  }

  search(v: string): void {
    this.q.set(v);
    void this.list.load(() => this.api.programs(v.trim()));
  }

  goTo(line: number): void {
    const count = this.prog.data()?.source.length ?? 0;
    if (!line || line < 1 || line > count) return;
    this.target.set(line);
    this.gotoText.set(String(line));
    this.scrollToLine(line, ++this.scrollRequest, 30);
  }

  /** The viewport takes its size a few frames after it appears, and clamps a scroll until then. A newer request wins. */
  private scrollToLine(line: number, request: number, framesLeft: number): void {
    if (request !== this.scrollRequest) return;
    // A few lines of context above the one asked for.
    const index = Math.max(0, line - 6);
    const v = this.viewport();
    v?.scrollToIndex(index);
    const arrived = v && (Math.abs(v.measureScrollOffset('top') - index * LINE_PX) <= 1 || (v.getDataLength() > 0 && v.measureScrollOffset('top') > 0 && v.measureScrollOffset('bottom') <= 1));
    if (!arrived && framesLeft > 0) requestAnimationFrame(() => this.scrollToLine(line, request, framesLeft - 1));
  }

  nextHit(): void {
    const h = this.hits();
    if (!h.length) return;
    this.hitCursor = (this.hitCursor + 1) % h.length;
    this.goTo(h[this.hitCursor]);
  }

  /** A CALLS caller opens at the call; a JCL edge's line belongs to a different member, so it opens at the top. */
  openCaller(program: string, line: number | null, edgeType: string): void {
    void this.router.navigate(['/programs', program], { queryParams: edgeType === 'CALLS' && line ? { line } : {} });
  }
}
