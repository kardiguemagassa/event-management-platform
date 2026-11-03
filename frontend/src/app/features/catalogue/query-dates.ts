/** Days in the address as yyyy-MM-dd, read and written in the user's time zone (a day, not an instant). */
const DAY = /^(\d{4})-(\d{2})-(\d{2})$/;

export function toQueryDate(day: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${day.getFullYear()}-${pad(day.getMonth() + 1)}-${pad(day.getDate())}`;
}

/** Midnight of that day, local time; {@code null} when absent or malformed (the address can be edited by hand). */
export function fromQueryDate(value: string | null | undefined): Date | null {
  const match = value ? DAY.exec(value) : null;
  if (!match) {
    return null;
  }
  const [year, month, day] = [Number(match[1]), Number(match[2]) - 1, Number(match[3])];
  const date = new Date(year, month, day);
  return date.getFullYear() === year && date.getMonth() === month && date.getDate() === day
    ? date
    : null;
}

export function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}
