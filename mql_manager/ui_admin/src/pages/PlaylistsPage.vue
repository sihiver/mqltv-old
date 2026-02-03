<template>
  <AdminShell title="Playlists" :auth-required="authRequired">
    <el-card>
      <div style="display:flex; justify-content:space-between; align-items:center; gap: 12px; flex-wrap: wrap;">
        <div style="display:flex; gap: 10px; align-items:center;">
          <el-button @click="load" :loading="loading">Refresh</el-button>
        </div>
        <div style="display:flex; gap: 10px; align-items:center;">
          <el-button @click="showUrl = true">Import URL</el-button>
          <el-button type="primary" @click="showUpload = true">Upload M3U</el-button>
        </div>
      </div>

      <el-alert v-if="error" :title="error" type="error" show-icon style="margin: 12px 0" />

      <el-table :data="items" style="width:100%; margin-top: 12px" v-loading="loading">
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column prop="name" label="Name" min-width="220" />
        <el-table-column prop="sourceType" label="Type" width="110" />
        <el-table-column prop="sourceUrl" label="Source URL" min-width="280" />
        <el-table-column prop="createdAt" label="Created" width="240" />
        <el-table-column label="Public URL" min-width="260">
          <template #default="scope">
            <div style="display:flex; gap:8px; align-items:center;">
              <el-input :model-value="scope.row.publicUrl" readonly size="small" />
              <el-button size="small" @click="copy(scope.row.publicUrl)">Copy</el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="Actions" width="260">
          <template #default="scope">
            <el-button
              size="small"
              type="primary"
              plain
              @click="reimport(scope.row.id)"
              :loading="reimportingId === scope.row.id"
            >
              Re-import
            </el-button>
            <el-button size="small" type="danger" plain @click="remove(scope.row.id)" :loading="deletingId === scope.row.id">
              Delete
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="showUrl" title="Import playlist from URL" width="560px">
      <el-form label-position="top">
        <el-form-item label="Name">
          <el-input v-model="urlName" placeholder="e.g. Premium IPTV" />
        </el-form-item>
        <el-form-item label="M3U URL">
          <el-input v-model="urlValue" placeholder="https://.../playlist.m3u" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showUrl = false">Cancel</el-button>
        <el-button type="primary" :loading="creatingUrl" @click="createFromUrl">Import</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showUpload" title="Upload M3U file" width="560px">
      <el-form label-position="top">
        <el-form-item label="Name">
          <el-input v-model="uploadName" placeholder="e.g. Local M3U" />
        </el-form-item>
        <el-form-item label="File">
          <input type="file" accept=".m3u,.m3u8,application/x-mpegURL,audio/x-mpegurl" @change="onFile" />
          <div v-if="uploadFile" style="margin-top:8px; color:#64748b; font-size:12px;">
            Selected: {{ uploadFile.name }} ({{ Math.round(uploadFile.size / 1024) }} KB)
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showUpload = false">Cancel</el-button>
        <el-button type="primary" :loading="uploading" @click="upload">Upload</el-button>
      </template>
    </el-dialog>
  </AdminShell>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminShell from '@/components/AdminShell.vue'
import { api, type Playlist } from '@/lib/api'

const items = ref<Playlist[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const authRequired = ref<boolean | undefined>(undefined)

const deletingId = ref<number | null>(null)
const reimportingId = ref<number | null>(null)

const showUrl = ref(false)
const urlName = ref('')
const urlValue = ref('')
const creatingUrl = ref(false)

const showUpload = ref(false)
const uploadName = ref('')
const uploadFile = ref<File | null>(null)
const uploading = ref(false)

async function load() {
  loading.value = true
  error.value = null
  try {
    const h = await api.health()
    authRequired.value = h.authRequired
    items.value = await api.listPlaylists()
  } catch (e: any) {
    error.value = e?.message || 'Failed to load'
  } finally {
    loading.value = false
  }
}

async function createFromUrl() {
  creatingUrl.value = true
  try {
    const p = await api.createPlaylistFromURL({ name: urlName.value.trim(), url: urlValue.value.trim() })
    ElMessage.success('Imported')
    showUrl.value = false
    urlName.value = ''
    urlValue.value = ''
    items.value = [p, ...items.value]
  } catch (e: any) {
    ElMessage.error(e?.message || 'Import failed')
  } finally {
    creatingUrl.value = false
  }
}

function onFile(ev: Event) {
  const input = ev.target as HTMLInputElement
  uploadFile.value = input.files?.[0] || null
}

async function upload() {
  if (!uploadFile.value) {
    ElMessage.error('Choose a file first')
    return
  }
  uploading.value = true
  try {
    await api.uploadPlaylist({ name: uploadName.value.trim(), file: uploadFile.value })
    ElMessage.success('Uploaded')
    showUpload.value = false
    uploadName.value = ''
    uploadFile.value = null
    await load()
    // keep it simple: reload list to keep ordering consistent
  } catch (e: any) {
    ElMessage.error(e?.message || 'Upload failed')
  } finally {
    uploading.value = false
  }
}

async function remove(id: number) {
  try {
    await ElMessageBox.confirm('Delete this playlist?', 'Confirm', { type: 'warning' })
  } catch {
    return
  }

  deletingId.value = id
  try {
    await api.deletePlaylist(id)
    ElMessage.success('Deleted')
    items.value = items.value.filter((p) => p.id !== id)
  } catch (e: any) {
    ElMessage.error(e?.message || 'Delete failed')
  } finally {
    deletingId.value = null
  }
}

async function reimport(id: number) {
  reimportingId.value = id
  try {
    const res = await api.reimportPlaylist(id)
    ElMessage.success(`Re-imported ${res.imported} channels`)
  } catch (e: any) {
    ElMessage.error(e?.message || 'Re-import failed')
  } finally {
    reimportingId.value = null
  }
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('Copied')
  } catch {
    ElMessage.info(text)
  }
}

onMounted(load)
</script>
