<template>
  <div class="page" v-if="detail">
    <div class="page-head">
      <h2 class="page-title">
        隔离详情 · {{ detail.isolation.isolationNo }}
        <el-tag :type="isolationTagType(detail.isolation.status)" size="small">
          {{ ISOLATION_STATUS[detail.isolation.status] }}
        </el-tag>
        <em>{{ detail.isolation.cabinetCode }} · {{ detail.isolation.cabinetName }}
          （{{ detail.isolation.roomName || '—' }}）</em>
      </h2>
      <div class="page-tools">
        <el-button size="small" @click="back">返回台账</el-button>
        <el-button size="small" @click="load">刷新</el-button>
        <el-button v-if="detail.isolation.status === 'PENDING'" type="warning" size="small" @click="openEffect">
          生效
        </el-button>
        <el-button v-if="detail.isolation.status === 'ISOLATING'" type="success" size="small" @click="openRelease">
          解除
        </el-button>
        <el-button v-if="detail.isolation.status === 'PENDING'" type="danger" size="small" @click="openCancel">
          取消
        </el-button>
      </div>
    </div>

    <!-- 待生效预检提示 -->
    <el-alert
      v-if="detail.isolation.status === 'PENDING'"
      :title="detail.preflightMessage"
      :type="detail.preflightBlocked ? 'error' : 'success'"
      :closable="false"
      show-icon
      class="preflight"
    />
    <el-alert
      v-if="detail.isolation.status === 'PENDING' && detail.isolation.lastFailReason"
      :title="`上次生效失败：${detail.isolation.lastFailReason}`"
      type="error"
      :closable="false"
      show-icon
      class="preflight"
    />

    <!-- ① 计划信息 -->
    <div class="panel">
      <div class="panel-title">计划信息</div>
      <div class="kv-grid">
        <div class="kv"><span>隔离机柜</span><b>{{ detail.isolation.cabinetCode }} · {{ detail.isolation.cabinetName }}</b></div>
        <div class="kv"><span>所属机房</span><b>{{ detail.isolation.roomName || '—' }}</b></div>
        <div class="kv"><span>计划开始</span><b>{{ fmtTime(detail.isolation.planStart) }}</b></div>
        <div class="kv"><span>计划结束</span><b>{{ fmtTime(detail.isolation.planEnd) }}</b></div>
        <div class="kv"><span>发起人</span><b>{{ detail.isolation.initiator }}</b></div>
        <div class="kv"><span>现场负责人</span><b>{{ detail.isolation.siteOwner }}</b></div>
        <div class="kv"><span>创建时间</span><b>{{ fmtTime(detail.isolation.createTime) }}</b></div>
        <div class="kv"><span>实际生效</span>
          <b :class="{ 'iso-live': detail.isolation.effectiveTime }">
            {{ fmtTime(detail.isolation.effectiveTime) }}
          </b>
        </div>
        <div class="kv kv-wide"><span>检修原因</span><b>{{ detail.isolation.reason }}</b></div>
        <div class="kv kv-wide" v-if="detail.isolation.remark"><span>备注</span><b>{{ detail.isolation.remark }}</b></div>
        <template v-if="detail.isolation.status === 'RELEASED'">
          <div class="kv"><span>解除时间</span><b>{{ fmtTime(detail.isolation.releaseTime) }}</b></div>
          <div class="kv"><span>解除操作人</span><b>{{ detail.isolation.releaseUser }}</b></div>
          <div class="kv kv-wide"><span>解除说明</span><b>{{ detail.isolation.releaseNote }}</b></div>
        </template>
        <template v-if="detail.isolation.status === 'CANCELLED'">
          <div class="kv"><span>取消时间</span><b>{{ fmtTime(detail.isolation.cancelTime) }}</b></div>
          <div class="kv kv-wide"><span>取消原因</span><b>{{ detail.isolation.cancelReason }}</b></div>
        </template>
      </div>
    </div>

    <!-- ② 隔离时快照（不可变历史事实） -->
    <div class="panel">
      <div class="panel-title">
        隔离时影响范围快照
        <span class="qbe-badge">生效瞬间固定 {{ detail.servers.length }} 台 · 不受后续资产变更改写</span>
      </div>
      <el-table :data="detail.servers" size="small" border stripe>
        <el-table-column prop="assetNo" label="资产编号" width="120" />
        <el-table-column label="品牌型号" min-width="170">
          <template #default="{ row }">{{ row.brand }} {{ row.model }}</template>
        </el-table-column>
        <el-table-column label="快照 U 位" width="120">
          <template #default="{ row }">{{ row.startU }}U 起 / {{ row.sizeU }}U</template>
        </el-table-column>
        <el-table-column label="快照状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)">{{ SERVER_STATUS[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ownerName" label="业务归属" min-width="140" />
        <el-table-column label="与当前差异" min-width="220">
          <template #default="{ row }">
            <el-tag v-if="row.diffState === 'SAME'" size="small" type="success">与当前一致</el-tag>
            <div v-else class="diff-list">
              <el-tag size="small" :type="row.diffState === 'REMOVED' ? 'danger' : 'warning'">
                {{ row.diffState === 'REMOVED' ? '已移出/删除' : '已变化' }}
              </el-tag>
              <span v-for="(d, i) in row.diffs" :key="i" class="diff-item">{{ d }}</span>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!detail.snapshotReady && detail.isolation.status === 'PENDING'"
                description="尚未生效：生效时才会生成不可变快照" :image-size="60" />
    </div>

    <!-- ③ 当前实况（仅用于对照，不覆盖快照） -->
    <div class="panel">
      <div class="panel-title">
        当前服务器实况对照
        <span class="qbe-badge">实时查询，仅展示与快照的差异，历史事实以快照为准</span>
      </div>
      <el-table :data="detail.servers" size="small" border>
        <el-table-column prop="assetNo" label="资产编号" width="120" />
        <el-table-column label="当前 U 位" width="130">
          <template #default="{ row }">
            <span v-if="row.currentMissing" class="muted">资产已不存在</span>
            <span v-else>{{ row.currentStartU }}U 起 / {{ row.currentSizeU }}U</span>
          </template>
        </el-table-column>
        <el-table-column label="当前状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="!row.currentMissing" size="small" :type="statusTagType(row.currentStatus)">
              {{ SERVER_STATUS[row.currentStatus] }}
            </el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="当前业务归属" min-width="140">
          <template #default="{ row }">
            <span v-if="row.currentMissing" class="muted">—</span>
            <span v-else>{{ row.currentOwnerName || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="当前所在机柜" width="130">
          <template #default="{ row }">
            <span v-if="row.currentMissing" class="muted">—</span>
            <span v-else :class="{ 'diff-hot': row.currentCabinetId !== detail.isolation.cabinetId }">
              机柜 #{{ row.currentCabinetId }}
              <em v-if="row.currentCabinetId !== detail.isolation.cabinetId">（已迁出）</em>
            </span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- ④ 冲突 / 冻结施工 -->
    <div class="panel">
      <div class="panel-title">
        相关上架 / 迁移施工
        <span class="qbe-badge">
          施工中工单在生效前即硬冲突拦截；待审批/已批复工单隔离期间冻结
        </span>
      </div>
      <el-table :data="detail.orders" size="small" border stripe>
        <el-table-column prop="orderNo" label="工单号" width="150" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ ORDER_TYPE[row.orderType] }}</template>
        </el-table-column>
        <el-table-column prop="assetNo" label="资产编号" width="120" />
        <el-table-column label="生效时状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)">{{ ORDER_STATUS[row.status] }}</el-tag>
            <el-tag v-if="row.conflictFlag === 1" size="small" type="danger" effect="dark" style="margin-left: 4px">
              硬冲突
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="!row.currentMissing" size="small" :type="statusTagType(row.currentStatus)">
              {{ ORDER_STATUS[row.currentStatus] }}
            </el-tag>
            <span v-else class="muted">已不存在</span>
          </template>
        </el-table-column>
        <el-table-column label="关系" width="120">
          <template #default="{ row }">
            {{ row.relation === 'INTO_CAB' ? '迁入 / 上架进柜' : '本柜施工' }}
          </template>
        </el-table-column>
        <el-table-column label="此刻是否冻结" min-width="180">
          <template #default="{ row }">
            <el-tag v-if="row.frozenNow" size="small" type="danger" effect="plain">隔离中 · 禁止推进</el-tag>
            <span v-else class="muted">未冻结（已解除或工单已终态）</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!detail.orders.length" description="无相关施工工单" :image-size="60" />
    </div>

    <!-- ⑤ 操作记录 -->
    <div class="panel">
      <div class="panel-title">操作记录</div>
      <el-timeline>
        <el-timeline-item
          v-for="log in detail.logs"
          :key="log.id"
          :timestamp="`${fmtTime(log.logTime)} · ${log.operator || '—'}`"
          :type="logType(log.action)"
          placement="top"
        >
          <b>{{ logActionName(log.action) }}</b>
          <span class="log-detail">{{ log.detail }}</span>
        </el-timeline-item>
      </el-timeline>
    </div>

    <!-- 生效 / 解除 / 取消对话框 -->
    <el-dialog v-model="effectDialog" title="隔离生效确认" width="520px">
      <div class="confirm-box">
        <p>即将让 <b>{{ detail.isolation.cabinetCode }}</b> 的隔离单
          <b>{{ detail.isolation.isolationNo }}</b> 生效。</p>
        <ul class="confirm-rules">
          <li>一次性固定在架/维修服务器快照；冻结本柜相关待审批、已批复工单；</li>
          <li>存在施工中工单将整体拒绝，记录保持待生效并写明原因，可再次操作。</li>
        </ul>
        <el-input v-model="effectOperator" placeholder="生效操作人" size="small" />
      </div>
      <template #footer>
        <el-button @click="effectDialog = false">取消</el-button>
        <el-button type="warning" @click="submitEffect">确认生效</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="releaseDialog" title="解除隔离" width="520px">
      <el-form label-width="100px" size="small">
        <el-form-item label="现场负责人" required>
          <el-input v-model="releaseOperator" />
        </el-form-item>
        <el-form-item label="解除说明" required>
          <el-input v-model="releaseNote" type="textarea" :rows="4"
                    placeholder="检修完成情况、设备恢复供电与核验结果" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="releaseDialog = false">取消</el-button>
        <el-button type="success" @click="submitRelease">确认解除并恢复施工</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="cancelDialog" title="取消待生效隔离" width="480px">
      <el-form label-width="90px" size="small">
        <el-form-item label="取消操作人" required><el-input v-model="cancelOperator" /></el-form-item>
        <el-form-item label="取消原因" required>
          <el-input v-model="cancelReason" type="textarea" :rows="3" />
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
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ISOLATION_STATUS,
  ORDER_STATUS,
  ORDER_TYPE,
  SERVER_STATUS,
  fmtTime,
  isolationApi,
  statusTagType
} from '../api'

const route = useRoute()
const router = useRouter()
const detail = ref(null)

const effectDialog = ref(false)
const effectOperator = ref('')
const releaseDialog = ref(false)
const releaseNote = ref('')
const releaseOperator = ref('')
const cancelDialog = ref(false)
const cancelReason = ref('')
const cancelOperator = ref('')

function isolationTagType(status) {
  return { PENDING: 'warning', ISOLATING: 'danger', RELEASED: 'success', CANCELLED: 'info' }[status] || 'info'
}

function logType(action) {
  return { EFFECT: 'warning', FAIL: 'danger', RELEASE: 'success', CANCEL: 'info', CREATE: 'primary' }[action] || 'info'
}

function logActionName(action) {
  return { CREATE: '创建计划', EFFECT: '隔离生效', FAIL: '生效失败', RELEASE: '解除隔离', CANCEL: '取消计划' }[action] || action
}

async function load() {
  detail.value = await isolationApi.get(route.params.id)
}

function back() {
  router.push('/isolations')
}

function openEffect() {
  effectOperator.value = ''
  effectDialog.value = true
}

async function submitEffect() {
  if (!effectOperator.value.trim()) return ElMessage.warning('请填写生效操作人')
  await isolationApi.effect(route.params.id, effectOperator.value.trim())
  ElMessage.success('隔离已生效，相关施工已冻结')
  effectDialog.value = false
  await load()
}

function openRelease() {
  releaseNote.value = ''
  releaseOperator.value = ''
  releaseDialog.value = true
}

async function submitRelease() {
  if (!releaseOperator.value.trim()) return ElMessage.warning('请填写现场负责人')
  if (!releaseNote.value.trim()) return ElMessage.warning('必须填写解除说明')
  await isolationApi.release(route.params.id, releaseNote.value.trim(), releaseOperator.value.trim())
  ElMessage.success('隔离已解除，相关施工恢复正常推进')
  releaseDialog.value = false
  await load()
}

function openCancel() {
  cancelReason.value = ''
  cancelOperator.value = ''
  cancelDialog.value = true
}

async function submitCancel() {
  if (!cancelOperator.value.trim()) return ElMessage.warning('请填写取消操作人')
  if (!cancelReason.value.trim()) return ElMessage.warning('请填写取消原因')
  await isolationApi.cancel(route.params.id, cancelReason.value.trim(), cancelOperator.value.trim())
  ElMessage.success('隔离计划已取消')
  cancelDialog.value = false
  await load()
}

onMounted(load)
</script>

<style scoped>
.preflight {
  margin-bottom: 12px;
}

.kv-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px 14px;
}

.kv {
  display: flex;
  flex-direction: column;
  font-size: 12px;
}

.kv-wide {
  grid-column: span 2;
}

.kv span {
  color: var(--ink-3);
}

.kv b {
  font-weight: 600;
}

.iso-live {
  color: #c62828;
}

.diff-list {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.diff-item {
  font-size: 12px;
  color: var(--ink-2);
}

.diff-hot {
  color: #c62828;
  font-weight: 600;
}

.log-detail {
  margin-left: 8px;
  color: var(--ink-2);
  font-size: 12px;
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
