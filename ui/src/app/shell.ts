import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Auth } from './core/auth.service';

interface NavItem { path: string; label: string; icon: string; roles?: string[] }

/** Role column of the Module 7 table. Links, gaps and deviations are readable by everyone; only a lead can act on them. */
const NAV: NavItem[] = [
  { path: '/inventory', label: 'Inventory', icon: 'inventory_2' },
  { path: '/programs', label: 'Program explorer', icon: 'code' },
  { path: '/graph', label: 'Graph view', icon: 'hub' },
  { path: '/review', label: 'Review queue', icon: 'fact_check', roles: ['sme-reviewer', 'admin'] },
  { path: '/links', label: 'Link confirmation', icon: 'link' },
  { path: '/gaps', label: 'Gap report', icon: 'rule' },
  { path: '/deviations', label: 'Deviations', icon: 'alt_route' },
  { path: '/tracker', label: 'Migration tracker', icon: 'view_kanban' },
  { path: '/captures', label: 'Captures', icon: 'science', roles: ['developer', 'admin'] },
  { path: '/setup', label: 'Setup wizard', icon: 'tune', roles: ['admin'] },
  { path: '/admin', label: 'Admin', icon: 'admin_panel_settings', roles: ['admin'] },
];

@Component({
  selector: 'app-shell',
  imports: [FormsModule, MatToolbarModule, MatSidenavModule, MatListModule, MatIconModule, MatButtonModule, RouterOutlet, RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <mat-toolbar class="topbar">
      <a routerLink="/" class="brand">Mainframe Modernization Toolkit</a>
      <form class="searchbox" role="search" (submit)="search($event)">
        <mat-icon>search</mat-icon>
        <input name="q" type="search" placeholder="Search code, rules, summaries, modern code" aria-label="Search" data-testid="shell-search" [(ngModel)]="q" />
      </form>
      <span class="spacer"></span>
      <span class="who" data-testid="who">{{ auth.me()?.username }} <span class="muted">({{ auth.roles().join(', ') || 'no role' }})</span></span>
      <button matButton data-testid="sign-out" (click)="auth.signOut()">Sign out</button>
    </mat-toolbar>
    <mat-sidenav-container class="body">
      <mat-sidenav mode="side" opened [disableClose]="true" class="nav">
        <mat-nav-list aria-label="Pages" data-testid="nav">
          @for (n of items(); track n.path) {
            <a mat-list-item [routerLink]="n.path" routerLinkActive="active-link" #rla="routerLinkActive" [activated]="rla.isActive">
              <mat-icon matListItemIcon>{{ n.icon }}</mat-icon>
              <span matListItemTitle>{{ n.label }}</span>
            </a>
          }
        </mat-nav-list>
      </mat-sidenav>
      <mat-sidenav-content class="content"><router-outlet /></mat-sidenav-content>
    </mat-sidenav-container>
  `,
})
export class Shell {
  readonly auth = inject(Auth);
  private readonly router = inject(Router);
  readonly q = signal('');
  readonly items = computed(() => NAV.filter((n) => !n.roles || n.roles.some((r) => this.auth.roles().includes(r))));

  search(ev: Event): void {
    ev.preventDefault();
    const q = this.q().trim();
    if (q) void this.router.navigate(['/search'], { queryParams: { q } });
  }
}
