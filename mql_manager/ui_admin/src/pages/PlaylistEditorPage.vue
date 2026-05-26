<template>
  <AdminShell :title="title" :auth-required="authRequired">
    <el-card class="mql-card" style="border: 1px solid var(--mql-border)">
      <template #header>
        <div style="display:flex; justify-content:space-between; align-items:center; gap: 12px; flex-wrap: wrap;">
          <div>
            <strong>Editor playlist</strong>
            <span v-if="playlist" style="margin-left: 8px; color: #64748b; font-size: 12px">
              #{{ playlist.id }} · {{ playlist.contentFormat || 'm3u' }} · {{ playlist.sourceType }}
            </span>
          </div>
          <div style="display:flex; gap: 8px; flex-wrap: wrap;">
            <el-button size="small" @click="load" :loading="loading">Muat ulang</el-button>
            <el-button size="small" @click="formatJson" :disabled="!isJson">Format JSON</el-button>
            <el-button size="small" type="primary" plain @click="save" :loading="saving">Simpan</el-button>
            <el-button size="small" @click="goBack">Kembali</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="error" :title="error" type="error" show-icon style="margin-bottom: 12px" />
      <el-alert
        v-if="fetchedFromUrl"
        title="Konten diambil dari URL sumber. Setelah simpan, playlist disimpan sebagai inline di database."
        type="info"
        show-icon
        :closable="false"
        style="margin-bottom: 12px"
      />

      <el-form label-position="top" v-if="playlist">
        <el-row :gutter="12">
          <el-col :span="12" :xs="24">
            <el-form-item label="Nama playlist">
              <el-input v-model="name" placeholder="Nama playlist" />
            </el-form-item>
          </el-col>
          <el-col :span="12" :xs="24">
            <el-form-item label="Public URL">
              <el-input :model-value="playlist.publicUrl" readonly>
                <template #append>
                  <el-button @click="copy(playlist.publicUrl)">Copy</el-button>
                </template>
              </el-input>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="Isi M3U / JSON Vision+">
          <textarea
            v-model="content"
            class="playlist-editor"
            spellcheck="false"
            @keydown.tab.prevent="insertTab"
          />
          <div class="playlist-editor-meta">
            {{ lineCount }} baris · {{ sizeLabel }} ·
            <span v-if="isJson">JSON</span><span v-else>M3U / teks</span>
          </div>
        </el-form-item>
      </el-form>
    </el-card>
  </AdminShell>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AdminShell from '@/components/AdminShell.vue'
import { api, type Playlist } from '@/lib/api'

const route = useRoute()
const router = useRouter()

const playlistId = computed(() => Number(route.params.id))
const title = computed(() => (playlist.value ? `Playlist #${playlist.value.id}` : 'Playlist Editor'))

const playlist = ref<Playlist | null>(null)
const name = ref('')
const content = ref('')
const fetchedFromUrl = ref(false)
const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)
const authRequired = ref<boolean | undefined>(undefined)

const isJson = computed(() => {
  const t = content.value.trim()
  return t.startsWith('{') || t.startsWith('[')
})

const lineCount = computed(() => {
  if (!content.value) return 0
  return content.value.split('\n').length
})

const sizeLabel = computed(() => {
  const kb = new Blob([content.value]).size / 1024
  return kb >= 1024 ? `${(kb / 1024).toFixed(1)} MB` : `${Math.round(kb)} KB`
})

function insertTab(ev: KeyboardEvent) {
  const el = ev.target as HTMLTextAreaElement
  const start = el.selectionStart
  const end = el.selectionEnd
  content.value = content.value.slice(0, start) + '  ' + content.value.slice(end)
  requestAnimationFrame(() => {
    el.selectionStart = el.selectionEnd = start + 2
  })
}

async function load() {
  if (!Number.isFinite(playlistId.value) || playlistId.value <= 0) {
    error.value = 'ID playlist tidak valid'
    return
  }
  loading.value = true
  error.value = null
  try {
    const h = await api.health()
    authRequired.value = h.authRequired
    const res = await api.getPlaylistContent(playlistId.value)
    playlist.value = res.playlist
    name.value = res.playlist.name
    content.value = res.content
    fetchedFromUrl.value = !!res.fetchedFromUrl
  } catch (e: any) {
    error.value = e?.message || 'Gagal memuat playlist'
  } finally {
    loading.value = false
  }
}

function formatJson() {
  if (!isJson.value) {
    ElMessage.warning('Konten bukan JSON')
    return
  }
  try {
    const parsed = JSON.parse(content.value)
    content.value = JSON.stringify(parsed, null, 2) + '\n'
    ElMessage.success('JSON diformat')
  } catch (e: any) {
    ElMessage.error(e?.message || 'JSON tidak valid')
  }
}

async function save() {
  if (!content.value.trim()) {
    ElMessage.error('Isi playlist kosong')
    return
  }
  if (isJson.value) {
    try {
      JSON.parse(content.value)
    } catch (e: any) {
      ElMessage.error('JSON tidak valid: ' + (e?.message || ''))
      return
    }
  }
  saving.value = true
  try {
    const res = await api.savePlaylistContent(playlistId.value, {
      name: name.value.trim(),
      content: content.value
    })
    playlist.value = res.playlist
    fetchedFromUrl.value = false
    ElMessage.success(`Tersimpan — ${res.imported} channel di-import`)
  } catch (e: any) {
    ElMessage.error(e?.message || 'Gagal menyimpan')
  } finally {
    saving.value = false
  }
}

function goBack() {
  router.push('/playlists')
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('Disalin')
  } catch {
    ElMessage.info(text)
  }
}

onMounted(load)
</script>

<style scoped>
.playlist-editor {
  width: 100%;
  min-height: min(72vh, 720px);
  padding: 12px 14px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace;
  font-size: 13px;
  line-height: 1.45;
  resize: vertical;
  box-sizing: border-box;
  background: #0f172a;
  color: #e2e8f0;
}

.playlist-editor:focus {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}

.playlist-editor-meta {
  margin-top: 8px;
  color: #64748b;
  font-size: 12px;
}
</style>
