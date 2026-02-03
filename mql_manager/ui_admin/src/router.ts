import { createRouter, createWebHistory } from 'vue-router'
import LoginPage from './pages/LoginPage.vue'
import DashboardAnalysisPage from './pages/dashboard/DashboardAnalysisPage.vue'
import DashboardWorkplacePage from './pages/dashboard/DashboardWorkplacePage.vue'
import UsersPage from './pages/UsersPage.vue'
import UserDetailPage from './pages/UserDetailPage.vue'
import PlaylistsPage from './pages/PlaylistsPage.vue'
import { auth } from './lib/auth'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/dashboard/analysis' },
    { path: '/login', component: LoginPage, meta: { title: 'Login' } },

    {
      path: '/dashboard/analysis',
      component: DashboardAnalysisPage,
      meta: { requiresAuth: true, title: 'Analysis', breadcrumb: ['Dashboard', 'Analysis'] }
    },
    {
      path: '/dashboard/workplace',
      component: DashboardWorkplacePage,
      meta: { requiresAuth: true, title: 'Workplace', breadcrumb: ['Dashboard', 'Workplace'] }
    },

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
    }
  ]
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth && !auth.isLoggedIn()) {
    return { path: '/login', query: { next: to.fullPath } }
  }
  return true
})
