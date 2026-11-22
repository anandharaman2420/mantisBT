/** Turns pasted text (e.g. a column or a block copied from Excel, or a .csv/.tsv/.txt file) into rows of cells. */
export type Sep = 'auto' | 'tab' | 'semicolon' | 'comma' | 'none';

export const SEPARATORS: { value: Sep; label: string }[] = [
  { value: 'auto', label: 'Auto (tab if present, else whole line)' },
  { value: 'tab', label: 'Tab (paste from Excel)' },
  { value: 'semicolon', label: 'Semicolon ;' },
  { value: 'comma', label: 'Comma ,' },
  { value: 'none', label: 'None – whole line is one value' }
];

/** splits one line at `ch`, understanding "quoted, values" and "" escapes */
function splitLine(line: string, ch: string): string[] {
  const out: string[] = [];
  let cur = '', quoted = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (quoted) {
      if (c === '"') { if (line[i + 1] === '"') { cur += '"'; i++; } else quoted = false; }
      else cur += c;
    } else if (c === '"' && cur === '') quoted = true;
    else if (c === ch) { out.push(cur.trim()); cur = ''; }
    else cur += c;
  }
  out.push(cur.trim());
  return out;
}

export function parseRows(text: string, sep: Sep, skipHeader: boolean): string[][] {
  let lines = text.replace(/^\uFEFF/, '').split(/\r?\n/).filter(l => l.trim() !== '');
  if (skipHeader) lines = lines.slice(1);
  const mode = sep === 'auto' ? (lines.some(l => l.includes('\t')) ? 'tab' : 'none') : sep;
  const ch = mode === 'tab' ? '\t' : mode === 'semicolon' ? ';' : mode === 'comma' ? ',' : null;
  return lines.map(l => ch ? splitLine(l, ch) : [l.trim()]);
}

export function readFile(file: File): Promise<string> { return file.text(); }

export function truthy(s: string | undefined): boolean { return ['1', 'yes', 'y', 'true', 'x', 'ja', 'si'].includes((s ?? '').trim().toLowerCase()); }
