const STORAGE_KEY = 'mqlm_admin_token'

export const auth = {
  getToken(): string | null {
    const t = localStorage.getItem(STORAGE_KEY)
    return t && t.trim() ? t : null
  },
  setToken(token: string | null) {
    if (!token || !token.trim()) {
      localStorage.removeItem(STORAGE_KEY)
    } else {
      localStorage.setItem(STORAGE_KEY, token.trim())
    }
  },
  isLoggedIn(): boolean {
    // If backend auth is disabled, token may be empty. We'll treat "logged in" as
    // either having a token OR having explicitly chosen to continue without token.
    return localStorage.getItem(STORAGE_KEY) !== null
  },
  logout() {
    localStorage.removeItem(STORAGE_KEY)
  },
  markNoAuthMode() {
    // store empty string to indicate user chose to proceed without token
    localStorage.setItem(STORAGE_KEY, '')
  }
}
