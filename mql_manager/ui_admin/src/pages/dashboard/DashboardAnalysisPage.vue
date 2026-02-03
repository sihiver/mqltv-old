<template>
  <AdminShell title="Analysis" :auth-required="authRequired">
    <div class="mql-page">
      <el-row :gutter="12">
        <el-col :span="6" :xs="24">
          <el-card class="mql-card">
            <div class="mql-metric">
              <div>
                <div class="label">New users</div>
                <div class="value">{{ metrics.newUsers }}</div>
              </div>
              <el-icon size="28" color="#22c55e"><UserFilled /></el-icon>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6" :xs="24">
          <el-card class="mql-card">
            <div class="mql-metric">
              <div>
                <div class="label">Unread info</div>
                <div class="value">{{ metrics.unread }}</div>
              </div>
              <el-icon size="28" color="#60a5fa"><ChatDotRound /></el-icon>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6" :xs="24">
          <el-card class="mql-card">
            <div class="mql-metric">
              <div>
                <div class="label">Transaction</div>
                <div class="value">{{ metrics.tx }}</div>
              </div>
              <el-icon size="28" color="#f43f5e"><Money /></el-icon>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6" :xs="24">
          <el-card class="mql-card">
            <div class="mql-metric">
              <div>
                <div class="label">Total shopping</div>
                <div class="value">{{ metrics.shopping }}</div>
              </div>
              <el-icon size="28" color="#10b981"><ShoppingCart /></el-icon>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <el-row :gutter="12">
        <el-col :span="12" :xs="24">
          <el-card class="mql-card" style="height: 420px;">
            <template #header>
              <div style="display:flex; justify-content:space-between; align-items:center;">
                <strong>User access source</strong>
                <el-tag type="info" effect="plain">Demo</el-tag>
              </div>
            </template>
            <EChart :option="pieOption" height="340px" />
          </el-card>
        </el-col>
        <el-col :span="12" :xs="24">
          <el-card class="mql-card" style="height: 420px;">
            <template #header>
              <div style="display:flex; justify-content:space-between; align-items:center;">
                <strong>Weekly user activity</strong>
                <el-button size="small" @click="randomize">Randomize</el-button>
              </div>
            </template>
            <EChart :option="barOption" height="340px" />
          </el-card>
        </el-col>
      </el-row>
    </div>
  </AdminShell>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AdminShell from '@/components/AdminShell.vue'
import EChart from '@/components/charts/EChart.vue'
import { api } from '@/lib/api'
import { ChatDotRound, Money, ShoppingCart, UserFilled } from '@element-plus/icons-vue'

const authRequired = ref<boolean | undefined>(undefined)

const metrics = ref({
  newUsers: 102400,
  unread: 81212,
  tx: 9280,
  shopping: 13600
})

const access = ref([
  { name: 'Direct access', value: 35 },
  { name: 'Mail marketing', value: 12 },
  { name: 'Alliance advertising', value: 10 },
  { name: 'Video advertising', value: 8 },
  { name: 'Search engines', value: 35 }
])

const week = ref([13, 34, 26, 12, 24, 2, 3])

const pieOption = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: { left: 'left' },
  series: [
    {
      type: 'pie',
      radius: ['40%', '70%'],
      avoidLabelOverlap: false,
      itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
      label: { show: false },
      emphasis: { label: { show: true, fontSize: 14, fontWeight: 'bold' } },
      labelLine: { show: false },
      data: access.value
    }
  ]
}))

const barOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  xAxis: { type: 'category', data: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'] },
  yAxis: { type: 'value' },
  series: [
    {
      type: 'bar',
      data: week.value,
      itemStyle: { color: '#4f6ef7', borderRadius: [6, 6, 0, 0] }
    }
  ]
}))

function randomize() {
  week.value = week.value.map(() => Math.round(Math.random() * 35))
}

onMounted(async () => {
  try {
    const h = await api.health()
    authRequired.value = h.authRequired
  } catch {
    // ignore
  }
})
</script>
