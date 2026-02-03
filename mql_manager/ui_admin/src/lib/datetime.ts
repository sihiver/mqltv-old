export function formatDateTimeID(value: string | null | undefined): string {
  const s = String(value || '').trim()
  if (!s) return '—'

  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return s

  try {
    return new Intl.DateTimeFormat('id-ID', {
      dateStyle: 'medium',
      timeStyle: 'medium',
      hour12: false,
      timeZone: 'Asia/Jakarta'
    }).format(d)
  } catch {
    // Fallback without timezone option (older browsers)
    return new Intl.DateTimeFormat('id-ID', {
      dateStyle: 'medium',
      timeStyle: 'medium',
      hour12: false
    }).format(d)
  }
}

export function formatDateID(value: string | null | undefined): string {
  const s = String(value || '').trim()
  if (!s) return '—'

  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return s

  try {
    return new Intl.DateTimeFormat('id-ID', {
      dateStyle: 'medium',
      timeZone: 'Asia/Jakarta'
    }).format(d)
  } catch {
    return new Intl.DateTimeFormat('id-ID', {
      dateStyle: 'medium'
    }).format(d)
  }
}
