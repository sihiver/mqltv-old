import { createRouter, createWebHistory } from 'vue-router'
import LoginPage from './pages/LoginPage.vue'
import DashboardWorkplacePage from './pages/dashboard/DashboardWorkplacePage.vue'
import UsersPage from './pages/UsersPage.vue'
import UserDetailPage from './pages/UserDetailPage.vue'
import PlaylistsPage from './pages/PlaylistsPage.vue'
import PackagesPage from './pages/PackagesPage.vue'
import PackageDetailPage from './pages/PackageDetailPage.vue'
import { auth } from './lib/auth'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/login', component: LoginPage, meta: { title: 'Login' } },

    {
      path: '/dashboard',
      component: DashboardWorkplacePage,
      meta: { requiresAuth: true, title: 'Dashboard', breadcrumb: ['Dashboard'] }
    },

    // Backward-compatible link
    { path: '/dashboard/workplace', redirect: '/dashboard' },

    { path: '/users', component: UsersPage, meta: { requiresAuth: true, title: 'Users', breadcrumb: ['Users'] } },
    {
      path: '/users/:id',
      component: UserDetailPage,
      meta: { requiresAuth: true, title: 'User Detail', breadcrumb: ['Users', 'Detail'] }
    },

    {
      path: '/playlists',
      component: PlaylistsPage,
      meta: { requiresAuth: true, title: 'Playlists', breadcrumb: ['Playlists'] }
    },
    {
      path: '/packages',
      component: PackagesPage,
      meta: { requiresAuth: true, title: 'Packages', breadcrumb: ['Packages'] }
    },
    {
      path: '/packages/:id',
      component: PackageDetailPage,
      meta: { requiresAuth: true, title: 'Package Detail', breadcrumb: ['Packages', 'Detail'] }
    }
  ]
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth && !auth.isLoggedIn()) {
    return { path: '/login', query: { next: to.fullPath } }
  }
  return true
})
