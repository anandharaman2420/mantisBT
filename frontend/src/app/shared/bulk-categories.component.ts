import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { errMsg } from '../core/auth.interceptor';
import { parseRows, readFile, Sep, SEPARATORS } from '../core/bulk-parse';
import { ImportResult } from '../core/models';
import { ImportResultComponent } from './import-result.component';

/** Add hundreds of categories (e.g. one per form) at once: paste a column from Excel or upload a file. */
@Component({
  selector: 'app-bulk-categories', standalone: true, imports: [FormsModule, ImportResultComponent],
  template: `
  <details class="panel" style="background:#f7fafc;margin-top:14px">
    <summary style="cursor:pointer;font-weight:600">⚡ Bulk add categories (paste a list or upload a file)</summary>
    <p class="muted" style="margin:8px 0">One category per line – copy a column from Excel and paste it here. Optional second column = default assignee (username).
      Names that already exist are skipped (their assignee is updated if you give one). Up to 5000 rows.</p>
    <textarea rows="8" [(ngModel)]="text" (ngModelChange)="parse()" placeholder="Form A-001&#10;Form A-002&#10;Form A-003&#9;jsmith"></textarea>
    <div class="row" style="margin-top:8px;align-items:flex-end">
      <div><label>…or upload (.csv .tsv .txt)</label><input type="file" accept=".csv,.tsv,.txt,text/plain,text/csv" (change)="load($event)" style="width:auto"></div>
      <div><label>Column separator</label><select [(ngModel)]="sep" (ngModelChange)="parse()">@for (s of seps; track s.value) { <option [value]="s.value">{{ s.label }}</option> }</select></div>
      <label style="display:flex;gap:5px;align-items:center;font-weight:400;font-size:14px;color:inherit"><input type="checkbox" [(ngModel)]="header" (ngModelChange)="parse()"> first line is a header</label>
    </div>
    @if (rows.length) {
      <div class="muted" style="margin-top:8px"><b>{{ rows.length }}</b> rows found. Preview:
        @for (r of rows.slice(0, 5); track $index) { <span class="tag">{{ r[0] }}@if (r[1]) { → {{ r[1] }} }</span> } @if (rows.length > 5) { … }</div>
    }
    @if (error) { <div class="err" style="margin-top:8px">{{ error }}</div> }
    <div style="margin-top:10px"><button class="primary" (click)="run()" [disabled]="!rows.length || busy">{{ busy ? 'Importing…' : 'Import ' + rows.length + ' categories' }}</button></div>
    <app-import-result [r]="result" />
  </details>`
})
export class BulkCategoriesComponent {
  private api = inject(ApiService);
  @Input({ required: true }) projectId!: number;
  @Output() done = new EventEmitter<void>();
  seps = SEPARATORS; sep: Sep = 'auto'; header = false; text = '';
  rows: string[][] = []; busy = false; error = ''; result: ImportResult | null = null;

  parse() { this.rows = parseRows(this.text, this.sep, this.header); }
  async load(ev: Event) {
    const f = (ev.target as HTMLInputElement).files?.[0];
    if (!f) return;
    this.text = await readFile(f); this.parse();
  }
  run() {
    this.busy = true; this.error = ''; this.result = null;
    this.api.bulkCategories(this.projectId, this.rows.map(r => ({ name: r[0] ?? '', assignee: r[1] ?? '' }))).subscribe({
      next: r => { this.busy = false; this.result = r; this.done.emit(); },
      error: e => { this.busy = false; this.error = errMsg(e); }
    });
  }
}
