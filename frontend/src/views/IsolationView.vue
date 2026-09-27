<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">
        机柜停电检修隔离
        <em>待生效 → 隔离中 → 已解除 / 已取消 · 生效固定影响快照并冻结相关施工</em>
      </h2>
      <div class="page-tools">
        <el-button size="small" @click="reload">刷新</el-button>
        <el-button type="primary" size="small" @click="openCreate">新建隔离</el-button>
      </div>
    </div>

    <div class="stat-grid">
      <div v-for="card in statusCards" :key="card.code" class="stat-card" :class="card.cls">
        <div class="stat-label">{{ card.label }}</div>
        <div class="stat-value">{{ card.count }}<small>条</small></div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-title">
        隔离台账
        <span class="qbe-badge">同一机柜同一时刻只允许一条「隔离中」</span>
      </div>
      <div class="filter-bar">
        <el-radio-group v-model="query.status" size="small" @change="reload">
          <el-radio-button label="">全部</el-radio-button>
          <el-radio-button v-for="(name, code) in ISOLATION_STATUS" :key="code" :label="code">
            {{ name }}
          </el-radio-button>
        </el-radio-group>
        <el-select v-model="query.cabinetId" size="small" clearable filterable placeholder="机柜"
                   style="width: 200px" @change="reload">
          <el-option v-for="item in cabinets" :key="item.id" :value="item.id"
                     :label="`${item.code} · ${item.name}`" />
        </el-select>
        <el-input v-model="query.isolationNo" size="small" placeholder="隔离单号" clearable
                  style="width: 160px" @keyup.enter="reload" />
        <el-button size="small" type="primary" @click="reload">查询</el-button>
      </div>

      <el-table :data="rows" size="small" border stripe>
        <el-table-column prop="isolationNo" label="隔离单号" width="150" />
        <el-table-column label="机柜" width="170">
          <template #default="{ row }">
            {{ row.cabinetCode }} · {{ row.cabinetName }}
            <el-tag v-if="row.status === 'ISOLATING'" size="small" type="danger" effect="dark" class="iso-flag">
              隔离中
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="计划起止" width="280">
          <template #default="{ row }">
            {{ fmtTime(row.planStart) }} ~ {{ fmtTime(row.planEnd) }}
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="检修原因" min-width="200" show-overflow-tooltip />
        <el-table-column label="发起人 / 现场负责人" width="170">
          <template #default="{ row }">{{ row.initiator }} / {{ row.siteOwner }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="isolationTagType(row.status)" size="small">
              {{ ISOLATION_STATUS[row.status] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="影响 / 生效时间" width="180">
          <template #default="{ row }">
            <span v-if="row.snapshotCount != null">{{ row.snapshotCount }} 台服务器</span>
            <span v-else class="muted">未生效</span>
            <div class="muted">{{ row.effectiveTime ? fmtTime(row.effectiveTime) : '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button v-if="row.status === 'PENDING'" link type="warning" size="small" @click="openEffect(row)">
              生效
            </el-button>
            <el-button v-if="row.status === 'ISOLATING'" link type="success" size="small" @click="openRelease(row)">
              解除
            </el-button>
            <el-button v-if="row.status === 'PENDING'" link type="danger" size="small" @click="openCancel(row)">
              取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新建隔离 -->
    <el-dialog v-model="createDialog" title="新建机柜停电检修隔离" width="560px">
      <el-form :model="form" label-width="100px" size="small">
        <el-form-item label="隔离机柜" required>
          <el-select v-model="form.cabinetId" filterable style="width: 100%" placeholder="选择机柜">
            <el-option v-for="item in cabinets" :key="item.id" :value="item.id"
                       :label="`${item.code} · ${item.name}（${item.roomName || ''}）`">
              <span>{{ item.code }} · {{ item.name }}</span>
              <el-tag v-if="item.isolating" size="small" type="danger" style="margin-left: 8px">隔离中</el-tag>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="计划开始" required>
          <el-date-picker v-model="form.planStart" type="datetime" placeholder="开始时间"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="计划结束" required>
          <el-date-picker v-model="form.planEnd" type="datetime" placeholder="结束时间"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="检修原因" required>
          <el-input v-model="form.reason" type="textarea" :rows="2"
                    placeholder="例如：PDU B 路空开跳闸隐患，停电更换空开" />
        </el-form-item>
        <el-form-item label="发起人" required>
          <el-input v-model="form.initiator" placeholder="值班负责人" />
        </el-form-item>
        <el-form-item label="现场负责人" required>
          <el-input v-model="form.siteOwner" placeholder="现场施工负责人" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">提交（待生效）</el-button>
      </template>
    </el-dialog>

    <!-- 生效确认 -->
    <el-dialog v-model="effectDialog" title="隔离生效确认" width="520px">
      <div v-if="effectRow" class="confirm-box">
        <p>即将让 <b>{{ effectRow.cabinetCode }} · {{ effectRow.cabinetName }}</b> 的隔离单
          <b>{{ effectRow.isolationNo }}</b> 生效。</p>
        <ul class="confirm-rules">
          <li>系统将一次性固定当时在架/维修中的服务器清单（U 位、业务归属、状态），快照不再受后续资产变更影响；</li>
          <li>该机柜相关的待审批 / 已批复工单将被冻结，隔离解除前不得推进；</li>
          <li>若存在已进入施工中的工单，本次生效会被整体拒绝，记录保持待生效，可处理后再次生效。</li>
        </ul>
        <el-input v-model="effectOperator" placeholder="生效操作人" size="small" />
      </div>
      <template #footer>
        <el-button @click="effectDialog = false">取消</el-button>
        <el-button type="warning" :loading="effectLoading" @click="submitEffect">确认生效</el-button>
      </template>
    </el-dialog>

    <!-- 解除 -->
    <el-dialog v-model="releaseDialog" title="解除隔离" width="520px">
      <el-form label-width="100px" size="small">
        <el-form-item label="现场负责人" required>
          <el-input v-model="releaseOperator" placeholder="解除操作人" />
        </el-form-item>
        <el-form-item label="解除说明" required>
          <el-input v-model="releaseNote" type="textarea" :rows="4"
                    placeholder="必须填写：检修完成情况、受影响设备恢复供电与核验结果" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="releaseDialog = false">取消</el-button>
        <el-button type="success" @click="submitRelease">确认解除并恢复施工</el-button>
      </template>
    </el-dialog>

    <!-- 取消 -->
    <el-dialog v-model="cancelDialog" title="取消待生效隔离" width="480px">
      <el-form label-width="90px" size="small">
        <el-form-item label="取消操作人" required>
          <el-input v-model="cancelOperator" />
        </el-form-item>
        <el-form-item label="取消原因" required>
          <el-input v-model="cancelReason" type="textarea" :rows="3" placeholder="例如：检修计划改期" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cancelDialog = false">返回</el-button>
        <el-button type="danger" @click="submitCancel">确认取消</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ISOLATION_STATUS, cabinetApi, fmtTime, isolationApi } from '../api'

const router = useRouter()
const rows = ref([])
const cabinets = ref([])
const query = reactive({ status: '', cabinetId: null, isolationNo: '' })

const createDialog = ref(false)
const form = ref(blankForm())

const effectDialog = ref(false)
const effectRow = ref(null)
const effectOperator = ref('')
const effectLoading = ref(false)

const releaseDialog = ref(false)
const releaseRow = ref(null)
const releaseNote = ref('')
const releaseOperator = ref('')

const cancelDialog = ref(false)
const cancelRow = ref(null)
const cancelReason = ref('')
const cancelOperator = ref('')

function blankForm() {
  return { cabinetId: null, planStart: '', planEnd: '', reason: '', initiator: '', siteOwner: '', remark: '' }
}

const counter = computed(() => {
  const map = {}
  rows.value.forEach((row) => {
    map[row.status] = (map[row.status] || 0) + 1
  })
  return map
})

const statusCards = computed(() => [
  { code: 'PENDING', label: '待生效', count: counter.value.PENDING || 0, cls: '' },
  { code: 'ISOLATING', label: '隔离中', count: counter.value.ISOLATING || 0, cls: 'warn' },
  { code: 'RELEASED', label: '已解除', count: counter.value.RELEASED || 0, cls: '' },
  { code: 'CANCELLED', label: '已取消', count: counter.value.CANCELLED || 0, cls: 'bad' }
])

/** 隔离单状态标签：PENDING 待生效用工单色板之外的 warning 视觉，避免与「待审批」混淆 */
function isolationTagType(status) {
  return { PENDING: 'warning', ISOLATING: 'danger', RELEASED: 'success', CANCELLED: 'info' }[status] || 'info'
}

async function reload() {
  rows.value = await isolationApi.list({
    status: query.status || undefined,
    cabinetId: query.cabinetId || undefined,
    isolationNo: query.isolationNo || undefined
  })
}

function openCreate() {
  form.value = blankForm()
  createDialog.value = true
}

async function submitCreate() {
  const f = form.value
  if (!f.cabinetId) return ElMessage.warning('请选择机柜')
  if (!f.planStart || !f.planEnd) return ElMessage.warning('请选择计划起止时间')
  if (!f.reason.trim()) return ElMessage.warning('请填写检修原因')
  if (!f.initiator.trim()) return ElMessage.warning('请填写发起人')
  if (!f.siteOwner.trim()) return ElMessage.warning('请填写现场负责人')
  await isolationApi.create(f)
  ElMessage.success('隔离计划已创建，状态为待生效')
  createDialog.value = false
  reload()
}

function openDetail(row) {
  router.push(`/isolations/${row.id}`)
}

function openEffect(row) {
  effectRow.value = row
  effectOperator.value = ''
  effectDialog.value = true
}

async function submitEffect() {
  if (!effectOperator.value.trim()) {
    ElMessage.warning('请填写生效操作人')
    return
  }
  effectLoading.value = true
  try {
    await isolationApi.effect(effectRow.value.id, effectOperator.value.trim())
    ElMessage.success('隔离已生效，相关施工已冻结')
    effectDialog.value = false
    reload()
  } finally {
    effectLoading.value = false
  }
}

function openRelease(row) {
  releaseRow.value = row
  releaseNote.value = ''
  releaseOperator.value = ''
  releaseDialog.value = true
}

async function submitRelease() {
  if (!releaseOperator.value.trim()) return ElMessage.warning('请填写现场负责人')
  if (!releaseNote.value.trim()) return ElMessage.warning('必须填写解除说明')
  await isolationApi.release(releaseRow.value.id, releaseNote.value.trim(), releaseOperator.value.trim())
  ElMessage.success('隔离已解除，相关施工恢复正常推进')
  releaseDialog.value = false
  reload()
}

function openCancel(row) {
  cancelRow.value = row
  cancelReason.value = ''
  cancelOperator.value = ''
  cancelDialog.value = true
}

async function submitCancel() {
  if (!cancelOperator.value.trim()) return ElMessage.warning('请填写取消操作人')
  if (!cancelReason.value.trim()) return ElMessage.warning('请填写取消原因')
  await isolationApi.cancel(cancelRow.value.id, cancelReason.value.trim(), cancelOperator.value.trim())
  ElMessage.success('隔离计划已取消')
  cancelDialog.value = false
  reload()
}

onMounted(async () => {
  cabinets.value = await cabinetApi.cabinets()
  await reload()
})
</script>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.iso-flag {
  margin-left: 6px;
}

.confirm-box {
  font-size: 13px;
  line-height: 1.8;
}

.confirm-rules {
  margin: 8px 0 14px;
  padding-left: 18px;
  color: var(--ink-2);
}
</style>
