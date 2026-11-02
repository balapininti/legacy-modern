import { ChangeDetectionStrategy, Component, ElementRef, OnDestroy, computed, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Router, RouterLink } from '@angular/router';
import type { Core } from 'cytoscape';
import { Api, GraphData, GraphEdge } from '../core/api';
import { Remote } from '../core/remote';
import { StateComponent } from '../core/state.component';

type View = 'calls' | 'job' | 'lineage';

/** Left to right, a column per step away from the sources, because node names are long: dataset names especially. */
function layered(nodes: string[], edges: GraphEdge[], root: string): Map<string, { x: number; y: number }> {
  const sources = nodes.filter((n) => !edges.some((e) => e.target === n && e.source !== n));
  const start = sources.length ? sources : nodes.filter((n) => n.toUpperCase() === root);
  const column = new Map<string, number>((start.length ? start : nodes.slice(0, 1)).map((n) => [n, 0]));
  const queue = [...column.keys()];
  while (queue.length) {
    const n = queue.shift()!;
    for (const e of edges) {
      if (e.source === n && !column.has(e.target)) {
        column.set(e.target, column.get(n)! + 1);
        queue.push(e.target);
      }
    }
  }
  const columns: string[][] = [];
  for (const n of nodes) (columns[column.get(n) ?? 0] ??= []).push(n);
  const out = new Map<string, { x: number; y: number }>();
  const tallest = Math.max(...columns.map((c) => c?.length ?? 0));
  let x = 0;
  for (const c of columns) {
    if (!c) continue;
    const width = Math.max(...c.map((n) => n.length)) * 7 + 24;
    c.forEach((n, i) => out.set(n, { x: x + width / 2, y: (i - (c.length - 1) / 2) * 44 + (tallest * 44) / 2 }));
    x += width + 110;
  }
  return out;
}

@Component({
  selector: 'app-graph',
  imports: [FormsModule, RouterLink, MatButtonModule, MatButtonToggleModule, MatCheckboxModule, MatFormFieldModule, MatInputModule, MatSelectModule, StateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .canvas { height: calc(100vh - 230px); min-height: 320px; border: 1px solid var(--line); background: #fbfbfc; }
    .layout { display: grid; grid-template-columns: minmax(0, 1fr) 400px; gap: 12px; }
    .edges { max-height: calc(100vh - 230px); overflow: auto; border: 1px solid var(--line); }
    .edges td { overflow-wrap: anywhere; }
    .filters mat-form-field.wide { width: 240px; }
    .filters mat-form-field.small { width: 80px; }
    .types { display: flex; flex-wrap: wrap; gap: 0 8px; align-items: center; margin-bottom: 6px; min-height: 28px; }
  `,
  template: `
    <h1>Graph view</h1>
    <div class="filters">
      <mat-button-toggle-group [value]="view()" (change)="setView($event.value)" hideSingleSelectionIndicator aria-label="Graph">
        <mat-button-toggle value="calls" data-testid="view-calls">Call graph</mat-button-toggle>
        <mat-button-toggle value="job" data-testid="view-job">Job flow</mat-button-toggle>
        <mat-button-toggle value="lineage" data-testid="view-lineage">Field lineage</mat-button-toggle>
      </mat-button-toggle-group>

      @if (view() === 'calls') {
        <mat-form-field class="wide">
          <mat-label>Program</mat-label>
          <input matInput list="program-names" data-testid="graph-program" [ngModel]="subject()" (ngModelChange)="subject.set($event)" (keydown.enter)="draw()" />
          <datalist id="program-names">@for (p of programNames(); track p) { <option [value]="p"></option> }</datalist>
        </mat-form-field>
        <mat-form-field>
          <mat-label>Direction</mat-label>
          <mat-select [ngModel]="direction()" (ngModelChange)="direction.set($event); draw()">
            <mat-option value="both">Both</mat-option>
            <mat-option value="callers">Callers</mat-option>
            <mat-option value="callees">Callees</mat-option>
          </mat-select>
        </mat-form-field>
      }
      @if (view() === 'job') {
        <mat-form-field class="wide">
          <mat-label>Job</mat-label>
          <input matInput list="job-names" data-testid="graph-job" [ngModel]="subject()" (ngModelChange)="subject.set($event)" (keydown.enter)="draw()" />
          <datalist id="job-names">@for (j of jobNames(); track j) { <option [value]="j"></option> }</datalist>
        </mat-form-field>
      }
      @if (view() === 'lineage') {
        <mat-form-field class="wide">
          <mat-label>Data item</mat-label>
          <input matInput data-testid="graph-item" [ngModel]="subject()" (ngModelChange)="subject.set($event)" (keydown.enter)="draw()" />
        </mat-form-field>
        <mat-form-field>
          <mat-label>In program</mat-label>
          <input matInput list="program-names" data-testid="graph-scope" [ngModel]="scope()" (ngModelChange)="scope.set($event)" (keydown.enter)="draw()" />
          <datalist id="program-names">@for (p of programNames(); track p) { <option [value]="p"></option> }</datalist>
        </mat-form-field>
      }
      @if (view() !== 'job') {
        <mat-form-field class="small">
          <mat-label>Depth</mat-label>
          <input matInput type="number" min="1" [max]="view() === 'lineage' ? 12 : 10" data-testid="graph-depth" [ngModel]="depth()" (ngModelChange)="depth.set(+$event)" (keydown.enter)="draw()" />
        </mat-form-field>
      }
      <button matButton="filled" data-testid="graph-draw" (click)="draw()">Draw</button>
      <mat-form-field>
        <mat-label>Filter nodes</mat-label>
        <input matInput data-testid="graph-filter" [ngModel]="filter()" (ngModelChange)="filter.set($event)" />
      </mat-form-field>
    </div>

    <app-state [r]="graph" [empty]="!graph.data()?.edges?.length" [emptyText]="emptyText()">
      <div class="types">
        <span class="muted">Edge types:</span>
        @for (t of edgeTypes(); track t) {
          <mat-checkbox [checked]="!hidden().has(t)" (change)="toggle(t)">{{ t }}</mat-checkbox>
        }
        <span class="spacer"></span>
        <span class="muted" data-testid="graph-counts">{{ visibleNodes().length }} nodes, {{ visibleEdges().length }} edges</span>
      </div>
      <div class="layout">
        <div #canvas class="canvas" data-testid="cy" role="img" [attr.aria-label]="'Graph of ' + graph.data()?.root"></div>
        <div class="edges">
          <table class="grid" data-testid="graph-edges">
            <thead><tr><th>From</th><th>Edge</th><th>To</th><th class="num">Line</th></tr></thead>
            <tbody>
              @for (e of visibleEdges(); track $index) {
                <tr>
                  <td>@if (view() === 'calls') { <a [routerLink]="['/programs', e.source]">{{ e.source }}</a> } @else { {{ e.source }} }</td>
                  <td class="muted">{{ e.type }}</td>
                  <td>@if (view() === 'calls') { <a [routerLink]="['/programs', e.target]">{{ e.target }}</a> } @else { {{ e.target }} }</td>
                  <td class="num">{{ e.line }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    </app-state>
  `,
})
export class GraphPage implements OnDestroy {
  private readonly api = inject(Api);
  private readonly router = inject(Router);

  /** Query parameters, bound by the router, so a graph can be linked to. */
  readonly viewParam = input<string>(undefined, { alias: 'view' });
  readonly programParam = input<string>(undefined, { alias: 'program' });
  readonly jobParam = input<string>(undefined, { alias: 'job' });
  readonly itemParam = input<string>(undefined, { alias: 'item' });

  readonly view = signal<View>('calls');
  readonly subject = signal('');
  readonly scope = signal('');
  readonly depth = signal(2);
  readonly direction = signal('both');
  readonly filter = signal('');
  readonly hidden = signal(new Set<string>());
  readonly graph = new Remote<GraphData>();
  readonly programNames = signal<string[]>([]);
  readonly jobNames = signal<string[]>([]);
  readonly emptyText = signal('Choose what to draw above.');

  private readonly canvas = viewChild<ElementRef<HTMLDivElement>>('canvas');
  private cy: Core | null = null;

  readonly edgeTypes = computed(() => [...new Set((this.graph.data()?.edges ?? []).map((e) => String(e.type)))].sort());
  readonly visibleEdges = computed<GraphEdge[]>(() => {
    const f = this.filter().trim().toUpperCase();
    return (this.graph.data()?.edges ?? []).filter(
      (e) => !this.hidden().has(String(e.type)) && (!f || e.source.toUpperCase().includes(f) || e.target.toUpperCase().includes(f)),
    );
  });
  readonly visibleNodes = computed(() => [...new Set(this.visibleEdges().flatMap((e) => [e.source, e.target]))]);

  constructor() {
    void this.api.programs().then((r) => this.programNames.set(r.map((p) => p.program_id))).catch(() => undefined);
    void this.api.jobNames().then((r) => this.jobNames.set(r.map((j) => j.name))).catch(() => undefined);

    // A link with a subject draws straight away.
    effect(() => {
      const v = (this.viewParam() as View) ?? 'calls';
      const s = v === 'job' ? this.jobParam() : v === 'lineage' ? this.itemParam() : this.programParam();
      untracked(() => {
        this.view.set(v);
        if (v === 'lineage') this.scope.set(this.programParam() ?? '');
        if (s) {
          this.subject.set(s);
          this.draw();
        }
      });
    });
    // The drawing follows the data, the filter and the edge-type boxes, and the canvas appearing.
    effect(() => {
      const el = this.canvas()?.nativeElement;
      const nodes = this.visibleNodes();
      const edges = this.visibleEdges();
      const root = this.graph.data()?.root;
      if (el && nodes.length) void this.render(el, nodes, edges, root ?? '');
      else this.dispose();
    });
  }

  ngOnDestroy(): void {
    this.dispose();
  }

  setView(v: View): void {
    this.view.set(v);
    this.subject.set('');
    this.filter.set('');
    this.graph.data.set(null);
    this.emptyText.set('Choose what to draw above.');
    this.hidden.set(new Set());
  }

  toggle(t: string): void {
    const next = new Set(this.hidden());
    if (!next.delete(t)) next.add(t);
    this.hidden.set(next);
  }

  draw(): void {
    const s = this.subject().trim();
    if (!s) return;
    const v = this.view();
    const d = Math.max(1, this.depth() || 1);
    this.hidden.set(new Set());
    this.emptyText.set(v === 'calls' ? `${s} has no call edges.` : v === 'job' ? `No job named ${s}, or it has no steps.` : `No MOVE lineage from ${s}. Pick the owning program, or check the name.`);
    void this.graph.load(() =>
      v === 'calls' ? this.api.callGraph(s, d, this.direction()) : v === 'job' ? this.api.jobGraph(s) : this.api.lineage(s, this.scope().trim(), d),
    );
  }

  private dispose(): void {
    this.cy?.destroy();
    this.cy = null;
  }

  private async render(el: HTMLDivElement, nodes: string[], edges: GraphEdge[], root: string): Promise<void> {
    const { default: cytoscape } = await import('cytoscape');
    this.dispose();
    const isCalls = this.view() === 'calls';
    // The table lists every edge with its line. The drawing shows one arrow per pair and type, with a count.
    const drawn = new Map<string, { source: string; target: string; type: string; n: number }>();
    for (const e of edges) {
      const type = e.type ?? '';
      const key = JSON.stringify([e.source, e.target, type]);
      const d = drawn.get(key);
      if (d) d.n++;
      else drawn.set(key, { source: e.source, target: e.target, type, n: 1 });
    }
    const positions = layered(nodes, edges, root);
    const cy = cytoscape({
      container: el,
      // Names reach the canvas as text labels; nothing here is ever HTML.
      elements: [
        ...nodes.map((id) => ({ data: { id, label: id, root: id.toUpperCase() === root ? 1 : 0, w: id.length * 6.8 + 12 }, position: positions.get(id) })),
        ...[...drawn.values()].map((e) => ({ data: { source: e.source, target: e.target, label: e.n > 1 ? `${e.type} x${e.n}` : e.type } })),
      ],
      style: [
        { selector: 'node', style: { label: 'data(label)', 'font-size': 11, 'text-valign': 'center', 'text-halign': 'center', width: 'data(w)', height: 24, padding: '4px', shape: 'round-rectangle', 'background-color': '#dbe7fb', 'border-width': 1, 'border-color': '#5b7db1', color: '#1b1f24' } },
        { selector: 'node[root = 1]', style: { 'background-color': '#14304f', color: '#fff', 'border-color': '#14304f' } },
        { selector: 'edge', style: { width: 1.2, 'line-color': '#8894a5', 'target-arrow-color': '#8894a5', 'target-arrow-shape': 'triangle', 'curve-style': 'bezier', label: 'data(label)', 'font-size': 9, color: '#5c6470', 'text-background-color': '#fbfbfc', 'text-background-opacity': 1, 'text-background-padding': '1px' } },
      ],
      layout: { name: 'preset', fit: true, padding: 24 },
    });
    // A small graph stays at reading size instead of filling the canvas.
    if (cy.zoom() > 1.2) {
      cy.zoom(1.2);
      cy.center();
    }
    if (isCalls) cy.on('tap', 'node', (ev) => void this.router.navigate(['/programs', ev.target.id()]));
    this.cy = cy;
    // For the browser test and for debugging: the canvas has no DOM to inspect.
    (el as unknown as { __cy: Core }).__cy = cy;
  }
}
