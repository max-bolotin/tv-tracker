export function formatMovieReleaseDate(releaseDate?: string): string | null {
  if (!releaseDate) return null;

  const fullDate = /^(\d{4})-(\d{2})-(\d{2})$/.exec(releaseDate);
  const yearMonth = /^(\d{4})-(\d{2})$/.exec(releaseDate);
  const yearOnly = /^(\d{4})$/.exec(releaseDate);
  const parts = fullDate ?? yearMonth;

  if (parts) {
    const year = Number(parts[1]);
    const month = Number(parts[2]);
    const day = fullDate ? Number(fullDate[3]) : 1;
    const date = new Date(Date.UTC(year, month - 1, day));
    if (
      date.getUTCFullYear() !== year
      || date.getUTCMonth() !== month - 1
      || date.getUTCDate() !== day
    ) {
      return releaseDate;
    }

    return new Intl.DateTimeFormat('en-US', {
      year: 'numeric',
      month: 'short',
      ...(fullDate ? { day: 'numeric' as const } : {}),
      timeZone: 'UTC',
    }).format(date);
  }

  return yearOnly ? yearOnly[1] : releaseDate;
}
