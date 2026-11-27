import { Component, effect, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { errMsg } from '../core/auth.interceptor';
import { MyView } from '../core/models';

/** Landing page of Mantis: the issues that matter to me, in boxes. Follows the project selector. */
@Component({
  selector: 'app-my-view', standalone: true, imports: [RouterLink, DatePipe],
  template: `
  <h1>My View @if (auth.currentProject()) { <span class="muted">– {{ auth.currentProject()!.name }}</span> }</h1>
  @if (error) { <div class="err">{{ error }}</div> }
  @if (data) {
    <div class="grid2">
      @for (b of data.boxes; track b.key) {
        <div class="panel box">
          <h2>{{ b.title }} <span class="muted">({{ b.total }})</span></h2>
          @for (i of b.items; track i.id) {
            <div class="row-i">
              <a [routerLink]="['/issues', i.id]" class="id">{{ i.id.toString().padStart(7, '0') }}</a>
              <span class="chip s{{ i.status }}">{{ auth.label('status', i.status) }}</span>
              <span class="sum">{{ i.summary }}</span>
              <span class="muted when">{{ i.lastUpdated | date:'MM-dd HH:mm' }}</span>
            </div>
          } @empty { <span class="muted">Nothing here.</span> }
          @if (b.total > b.items.length) { <div class="muted" style="margin-top:6px">… and {{ b.total - b.items.length }} more</div> }
        </div>
      }
    </div>
  }`,
  styles: [`
    .box { min-height: 120px; }
    .row-i { display: flex; gap: 8px; align-items: center; padding: 4px 0; border-bottom: 1px solid var(--border); }
    .row-i:last-of-type { border-bottom: 0; }
    .id { white-space: nowrap; } .sum { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } .when { white-space: nowrap; font-size: 12px; }
  `]
})
export class MyViewComponent {
  auth = inject(AuthService);
  private api = inject(ApiService);
  data: MyView | null = null; error = '';

  constructor() {
    effect(() => {
      const pid = this.auth.projectId();
      this.api.myView(pid).subscribe({ next: d => { this.data = d; this.error = ''; }, error: e => this.error = errMsg(e) });
    });
  }
}
