import { Component, Input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IssueFieldValue } from '../core/models';

/** Renders the right input control for one custom field; writes into the shared `values` map (fieldId -> value). */
@Component({
  selector: 'app-cf-input', standalone: true, imports: [FormsModule],
  template: `
  <label>{{ field.name }}@if (field.required) { <span> *</span> }</label>
  @switch (kind()) {
    @case ('area') { <textarea rows="3" [ngModel]="values[field.fieldId]" (ngModelChange)="set($event)"></textarea> }
    @case ('select') {
      <select [ngModel]="values[field.fieldId]" (ngModelChange)="set($event)">
        <option value="">–</option>@for (o of field.options; track o) { <option [value]="o">{{ o }}</option> }
      </select> }
    @case ('radio') {
      @for (o of field.options; track o) {
        <label class="opt"><input type="radio" [name]="'cf' + field.fieldId" [checked]="values[field.fieldId] === o" (change)="set(o)"> {{ o }}</label>
      }
      @if (!field.required) { <label class="opt"><input type="radio" [name]="'cf' + field.fieldId" [checked]="!values[field.fieldId]" (change)="set('')"> none</label> } }
    @case ('multi') { @for (o of field.options; track o) {
        <label class="opt"><input type="checkbox" [checked]="has(o)" (change)="toggle(o)"> {{ o }}</label> } }
    @case ('date') { <input type="date" [ngModel]="values[field.fieldId]" (ngModelChange)="set($event)"> }
    @case ('number') { <input type="number" [step]="field.type === 2 ? 'any' : 1" [ngModel]="values[field.fieldId]" (ngModelChange)="set($event)"> }
    @default { <input [type]="field.type === 4 ? 'email' : 'text'" [ngModel]="values[field.fieldId]" (ngModelChange)="set($event)"> }
  }`,
  styles: [`.opt { display: inline-flex; gap: 5px; align-items: center; margin-right: 12px; font-weight: 400; font-size: 14px; color: inherit; }`]
})
export class CfInputComponent {
  @Input({ required: true }) field!: IssueFieldValue;
  @Input({ required: true }) values!: Record<number, string>;

  kind(): string {
    switch (this.field.type) {
      case 10: return 'area';
      case 3: case 6: return 'select';
      case 9: return 'radio';
      case 5: case 7: return 'multi';
      case 8: return 'date';
      case 1: case 2: return 'number';
      default: return 'text';
    }
  }
  set(v: unknown) { this.values[this.field.fieldId] = v === null || v === undefined ? '' : String(v); }
  private parts(): string[] { return (this.values[this.field.fieldId] || '').split('|').filter(Boolean); }
  has(o: string) { return this.parts().includes(o); }
  toggle(o: string) {
    const p = this.parts();
    this.set((p.includes(o) ? p.filter(x => x !== o) : [...p, o]).join('|'));
  }
}
