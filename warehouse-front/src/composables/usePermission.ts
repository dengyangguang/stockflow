import { useAuthStore } from '@/stores/auth'

export function usePermission() {
  const authStore = useAuthStore()

  const hasPermission = (code: string): boolean => {
    if (authStore.permissions.includes('*:*')) return true
    const module = code.split(':')[0]
    return authStore.permissions.includes(code) || authStore.permissions.includes(`${module}:*`)
  }

  return { hasPermission }
}
