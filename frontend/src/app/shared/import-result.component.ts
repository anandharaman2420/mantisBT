import { Component, Input } from '@angular/core';
import { ImportResult } from '../core/models';

@Component({
  selector: 'app-import-result', standalone: true,
  template: `
  @if (r) {
    <div class="ok" style="margin-top:10px">
      @if (r.created) { <b>{{ r.created }}</b> created · } @if (r.updated) { <b>{{ r.updated }}</b> updated · }
      @if (r.removed) { <b>{{ r.removed }}</b> removed · } <b>{{ r.skipped }}</b> skipped
    </div>
    @if (r.problems.length) {
      <div class="err" style="max-height:220px;overflow:auto"><b>{{ r.problems.length }} note(s):</b>
        @for (p of r.problems; track $index) { <div>row {{ p.row }} – {{ p.value }}: {{ p.message }}</div> }</div>
    }
  }`
})
export class ImportResultComponent { @Input() r: ImportResult | null = null; }
