/** minutes -> "h:mm" (e.g. 90 -> "1:30") */
export function fmtMinutes(m: number | null | undefined): string {
  const v = Math.max(0, Math.round(m ?? 0));
  return `${Math.floor(v / 60)}:${String(v % 60).padStart(2, '0')}`;
}

/** "1:30" | "90" | "1.5h" | "45m" -> minutes, or null when not understood (empty string -> 0) */
export function parseTime(s: string): number | null {
  const t = (s ?? '').trim().toLowerCase();
  if (!t) return 0;
  let m = /^(\d+):([0-5]?\d)$/.exec(t);
  if (m) return +m[1] * 60 + +m[2];
  m = /^(\d+(?:[.,]\d+)?)\s*h$/.exec(t);
  if (m) return Math.round(parseFloat(m[1].replace(',', '.')) * 60);
  m = /^(\d+)\s*m?$/.exec(t);
  if (m) return +m[1];
  return null;
}
