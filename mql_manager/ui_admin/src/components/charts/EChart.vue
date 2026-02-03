<template>
  <div ref="el" :style="{ width: '100%', height }" />
</template>

<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{ option: any; height?: string }>()
const height = props.height || '320px'

const el = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function render() {
  if (!el.value) return
  if (!chart) chart = echarts.init(el.value)
  chart.setOption(props.option, true)
  chart.resize()
}

onMounted(() => {
  render()
  const ro = new ResizeObserver(() => chart?.resize())
  if (el.value) ro.observe(el.value)

  watch(
    () => props.option,
    () => render(),
    { deep: true }
  )

  onBeforeUnmount(() => {
    ro.disconnect()
    chart?.dispose()
    chart = null
  })
})
</script>
