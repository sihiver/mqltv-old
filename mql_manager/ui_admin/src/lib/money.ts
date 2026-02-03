export function formatIDR(amount: number): string {
  const n = Number.isFinite(amount) ? amount : 0
  return new Intl.NumberFormat('id-ID', {
    style: 'currency',
    currency: 'IDR',
    minimumFractionDigits: 0,
    maximumFractionDigits: 0
  }).format(n)
}
