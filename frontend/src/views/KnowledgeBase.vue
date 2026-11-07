<template>
  <section class="knowledge-page">
    <header class="kb-header">
      <div>
        <router-link v-if="route.path === '/knowledge'" to="/home"
          >← 返回工作台</router-link
        >
        <p class="eyebrow">NUTRITRACK / KNOWLEDGE</p>
        <h1>让建议有出处。</h1>
        <p>把可信资料放进知识库，让健康助手参考与你相关的内容。</p>
      </div>
      <router-link class="agent-link" to="/health-agent"
        >向健康助手提问 ↗</router-link
      >
    </header>
    <p v-if="error" class="kb-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="kb-notice" role="status">{{ notice }}</p>
    <div class="kb-layout">
      <div>
        <section class="kb-card import-card">
          <div class="card-top">
            <h2>{{ replaceId ? "更新文档" : "导入你的资料" }}</h2>
            <button v-if="replaceId" @click="cancelReplace">取消更新</button>
          </div>
          <p class="muted">
            个人文档仅本人可检索。支持文字版 PDF、UTF-8 TXT、Markdown，最大
            10MB；扫描件请先进行 OCR。
          </p>
          <form class="import-form" @submit.prevent="upload">
            <label
              >文档标题<input
                v-model="title"
                maxlength="160"
                placeholder="留空使用文件名"
                :disabled="importing"
            /></label>
            <label
              >来源链接（可选）<input
                v-model="sourceUrl"
                type="url"
                placeholder="https://…"
                maxlength="1000"
                :disabled="importing"
            /></label>
            <label class="file-picker"
              >选择文件<input
                ref="fileInput"
                type="file"
                accept=".pdf,.md,.txt"
                :disabled="importing"
                @change="selectFile"
            /></label>
            <button class="primary" :disabled="!file || importing || deleting">
              {{
                importing
                  ? "正在解析并建立索引…"
                  : replaceId
                    ? "替换文档并更新索引"
                    : "导入并建立索引"
              }}
            </button>
          </form>
        </section>
        <section class="kb-card document-card">
          <div class="card-top">
            <h2>
              可用文档 <span class="count">{{ documents.length }}</span>
            </h2>
            <button :disabled="loading" @click="load">刷新</button>
          </div>
          <p v-if="status" class="muted">
            共 {{ status.chunks }} 个文段 ·
            {{ status.personalDocuments }} 篇个人资料 · 索引已保存在本机
          </p>
          <p v-if="loading" role="status">正在加载文档…</p>
          <p v-else-if="!documents.length" class="muted">
            暂时没有文档，请先导入一份资料。
          </p>
          <article v-for="doc in documents" :key="doc.id" class="document-row">
            <div>
              <span class="doc-tag">{{
                doc.builtIn ? "公共 · 只读" : "我的文档"
              }}</span>
              <h3>{{ doc.title }}</h3>
              <p class="muted">
                {{ doc.fileName }} · {{ doc.pages }} 页 ·
                {{ doc.chunks }} 个文段 · v{{ doc.version }}
              </p>
            </div>
            <div class="row-actions">
              <button @click="showDocument(doc.id)">查看原文</button
              ><button
                v-if="!doc.builtIn"
                :disabled="importing"
                @click="prepareReplace(doc)"
              >
                更新</button
              ><button
                v-if="!doc.builtIn"
                class="danger"
                :disabled="deleting || importing"
                @click="remove(doc)"
              >
                删除
              </button>
            </div>
          </article>
        </section>
      </div>
      <aside class="kb-card search-card">
        <p class="eyebrow">找到回答的依据</p>
        <h2>检索试验台</h2>
        <p class="muted">
          对比不同方式找到的文段。这里展示检索结果，健康助手会进一步结合资料回答。
        </p>
        <form @submit.prevent="search">
          <label
            >问题<textarea
              v-model="query"
              maxlength="500"
              rows="3"
              placeholder="例如：少喝奶茶有什么依据？"
            />
          </label>
          <label
            >检索方式<select v-model="mode">
              <option value="hybrid">混合检索</option>
              <option value="bm25">关键词 BM25</option>
              <option value="vector">语义向量</option>
            </select></label
          >
          <button class="primary" :disabled="searching || !query.trim()">
            {{ searching ? "正在检索…" : "查找资料" }}
          </button>
        </form>
        <p v-if="result" class="muted">
          找到 {{ result.hits.length }} 个文段 · {{ result.durationMs }} ms
        </p>
        <p v-if="result && !result.hits.length" class="empty-result">
          当前资料不足，请换一种问法或导入相关文档。
        </p>
        <article v-for="hit in result?.hits || []" :key="hit.id" class="hit">
          <h3>{{ hit.title }}</h3>
          <p class="muted">
            第 {{ hit.page }} 页<span v-if="hit.section">
              · {{ hit.section }}</span
            >
          </p>
          <p class="excerpt">{{ hit.excerpt }}</p>
          <button
            @click="
              showDocument(hit.documentId, hit.chunkId, hit.documentVersion)
            "
          >
            定位这个文段 →
          </button>
          <details>
            <summary>检索详情</summary>
            <p>
              BM25：{{ score(hit.bm25Score) }} · 向量相似度：{{
                score(hit.vectorScore)
              }}
              · 排序分：{{ score(hit.score) }}
            </p>
            <p class="muted">分数用于排序，不代表答案正确率。</p>
          </details>
        </article>
      </aside>
    </div>
    <el-dialog
      v-model="detailOpen"
      :title="detail?.document.title || '文档原文'"
      width="min(900px, 94vw)"
      destroy-on-close
    >
      <p v-if="detailLoading" role="status">正在加载原文…</p>
      <template v-else-if="detail">
        <p
          v-if="
            requestedVersion && requestedVersion !== detail.document.version
          "
          class="kb-notice"
        >
          资料已经更新，当前展示 v{{ detail.document.version }}；原引用来自 v{{
            requestedVersion
          }}，请重新核对。
        </p>
        <p class="muted">
          {{ detail.document.fileName }} · v{{
            detail.document.version
          }}。以下为提取的原文文段，重叠内容会重复出现；文本文件页码统一为 1。
        </p>
        <a
          v-if="safeUrl(detail.document.sourceUrl)"
          :href="safeUrl(detail.document.sourceUrl)"
          target="_blank"
          rel="noopener noreferrer"
          >打开资料来源 ↗</a
        >
        <article
          v-for="chunk in detail.chunks"
          :id="`chunk-${chunk.id}`"
          :key="chunk.id"
          class="detail-chunk"
          :class="{ highlighted: selectedChunk === chunk.id }"
        >
          <p class="muted">
            第 {{ chunk.page }} 页 · {{ chunk.section || "正文" }} ·
            {{ chunk.id }}
          </p>
          <p class="excerpt">{{ chunk.text }}</p>
        </article>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick, watch } from "vue";
import { useRoute } from "vue-router";
import { ElMessageBox } from "element-plus";
import { isAuthError } from "@/utils/request";
import {
  knowledgeStatus,
  listDocuments,
  getDocument,
  deleteDocument,
  importDocument,
  searchDocuments,
} from "@/api/knowledge";

defineOptions({ name: "KnowledgeBase" });
const route = useRoute();
const controller = new AbortController();
const documents = ref([]),
  status = ref(null),
  loading = ref(false),
  error = ref(""),
  notice = ref("");
const title = ref(""),
  sourceUrl = ref(""),
  file = ref(null),
  fileInput = ref(null),
  replaceId = ref(""),
  importing = ref(false),
  deleting = ref(false);
const query = ref(""),
  mode = ref("hybrid"),
  searching = ref(false),
  result = ref(null);
const detail = ref(null),
  detailOpen = ref(false),
  detailLoading = ref(false),
  selectedChunk = ref("");
const requestedVersion = ref(null);
let detailRequest = 0;
function failed(e) {
  if (!controller.signal.aborted && !isAuthError(e))
    error.value = e.response?.data?.message || e.message || "操作失败，请重试";
}
async function load() {
  loading.value = true;
  try {
    const [docs, info] = await Promise.all([
      listDocuments(controller.signal),
      knowledgeStatus(controller.signal),
    ]);
    documents.value = docs;
    status.value = info;
  } catch (e) {
    failed(e);
  } finally {
    loading.value = false;
  }
}
function selectFile(event) {
  file.value = event.target.files?.[0] || null;
  if (file.value?.size > 10 * 1024 * 1024) {
    error.value = "文件最大10MB";
    file.value = null;
    event.target.value = "";
  }
}
function cancelReplace() {
  replaceId.value = "";
  title.value = "";
  sourceUrl.value = "";
  file.value = null;
  if (fileInput.value) fileInput.value.value = "";
}
function prepareReplace(doc) {
  cancelReplace();
  replaceId.value = doc.id;
  title.value = doc.title;
  sourceUrl.value = doc.sourceUrl;
  window.scrollTo({ top: 0, behavior: "smooth" });
}
async function upload() {
  if (!file.value || importing.value) return;
  importing.value = true;
  error.value = "";
  notice.value = "";
  try {
    const data = new FormData();
    data.append("file", file.value);
    data.append("title", title.value);
    data.append("sourceUrl", sourceUrl.value);
    const response = await importDocument(
      data,
      replaceId.value,
      controller.signal,
    );
    notice.value = response.duplicate
      ? "相同内容已经导入，已复用已有文档。"
      : `已完成索引：${response.document.chunks} 个文段。`;
    cancelReplace();
    result.value = null;
    await load();
  } catch (e) {
    failed(e);
  } finally {
    importing.value = false;
  }
}
async function remove(doc) {
  try {
    await ElMessageBox.confirm(`删除“${doc.title}”及其检索索引？`, "删除文档", {
      type: "warning",
    });
  } catch {
    return;
  }
  deleting.value = true;
  error.value = "";
  try {
    await deleteDocument(doc.id, controller.signal);
    result.value = null;
    notice.value = "文档及索引已删除。";
    if (replaceId.value === doc.id) cancelReplace();
    await load();
  } catch (e) {
    failed(e);
  } finally {
    deleting.value = false;
  }
}
async function search() {
  if (!query.value.trim() || searching.value) return;
  searching.value = true;
  error.value = "";
  result.value = null;
  try {
    result.value = await searchDocuments(
      { query: query.value.trim(), mode: mode.value, topK: 5 },
      controller.signal,
    );
  } catch (e) {
    failed(e);
  } finally {
    searching.value = false;
  }
}
async function showDocument(id, chunk = "", version = null) {
  const request = ++detailRequest;
  detailOpen.value = true;
  detailLoading.value = true;
  detail.value = null;
  selectedChunk.value = chunk;
  requestedVersion.value = Number(version) || null;
  try {
    const data = await getDocument(id, controller.signal);
    if (request !== detailRequest) return;
    detail.value = data;
    await nextTick();
    if (chunk)
      window.setTimeout(
        () =>
          document
            .getElementById(`chunk-${chunk}`)
            ?.scrollIntoView({ block: "center", behavior: "smooth" }),
        150,
      );
  } catch (e) {
    if (request === detailRequest) {
      detailOpen.value = false;
      failed(e);
    }
  } finally {
    if (request === detailRequest) detailLoading.value = false;
  }
}
function safeUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === "https:" && !url.username && !url.password
      ? url.href
      : "";
  } catch {
    return "";
  }
}
function score(value) {
  return typeof value === "number" ? value.toFixed(4) : "—";
}
function openFromRoute() {
  if (typeof route.query.documentId === "string")
    showDocument(
      route.query.documentId,
      typeof route.query.chunkId === "string" ? route.query.chunkId : "",
      route.query.version,
    );
}
watch(() => route.query, openFromRoute);
onMounted(() => {
  load();
  openFromRoute();
});
onBeforeUnmount(() => {
  detailRequest++;
  controller.abort();
});
</script>

<style scoped>
.knowledge-page {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif;
  max-width: 1320px;
  margin: auto;
  padding: 32px;
  color: #243e36;
  background: #f6f8f5;
  min-height: 100vh;
}
.kb-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  margin-bottom: 28px;
}
.kb-header h1 {
  font-size: 36px;
  letter-spacing: -1px;
  margin: 10px 0;
}
.kb-header p {
  color: #6b7e75;
}
.eyebrow {
  font-size: 11px;
  letter-spacing: 2px;
  color: #75897d;
}
.agent-link {
  white-space: nowrap;
}
.kb-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(300px, 0.85fr);
  gap: 24px;
  align-items: start;
}
.kb-card {
  background: #fff;
  border: 1px solid #e0e8e0;
  border-radius: 16px;
  padding: 24px;
  margin-bottom: 22px;
}
.kb-card h2 {
  font-size: 19px;
  margin: 0 0 12px;
}
.card-top {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 12px;
}
.count {
  font-size: 13px;
  color: #738477;
}
.muted {
  font-size: 12px;
  line-height: 1.7;
  color: #708176;
  overflow-wrap: anywhere;
}
.import-form {
  display: grid;
  gap: 12px;
}
label {
  display: block;
  font-size: 12px;
  color: #52685b;
  margin-bottom: 12px;
}
input,
textarea,
select {
  display: block;
  box-sizing: border-box;
  width: 100%;
  font: inherit;
  font-size: 14px;
  border: 1px solid #d9e4d9;
  background: #fafcf9;
  border-radius: 8px;
  padding: 10px;
  margin-top: 6px;
  color: #243e36;
}
textarea {
  resize: vertical;
}
button,
.agent-link {
  font: inherit;
  font-size: 12px;
  border: 0;
  background: #edf3ec;
  color: #365f45;
  padding: 9px 12px;
  border-radius: 7px;
  cursor: pointer;
  text-decoration: none;
}
button:disabled {
  opacity: 0.5;
  cursor: wait;
}
.primary {
  background: #365f45;
  color: white;
  padding: 12px 18px;
}
.document-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-top: 1px solid #edf1eb;
  padding: 18px 0;
}
.document-row h3,
.hit h3 {
  font-size: 15px;
  margin: 7px 0;
}
.doc-tag {
  font-size: 10px;
  color: #6c846e;
  background: #f0f5ed;
  padding: 3px 6px;
  border-radius: 4px;
}
.row-actions {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
  flex-shrink: 0;
}
.danger {
  color: #aa4a42;
  background: #fff1ed;
}
.hit {
  padding: 18px 0;
  border-top: 1px solid #e8eee5;
}
.excerpt {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 14px;
  line-height: 1.85;
}
.kb-error {
  padding: 14px;
  background: #fff0ec;
  color: #ad4c3e;
  border-radius: 8px;
}
.kb-notice {
  padding: 14px;
  background: #eaf3e8;
  color: #416943;
  border-radius: 8px;
}
.empty-result {
  padding: 20px;
  background: #f4f6f1;
  color: #6c7e6e;
  font-size: 13px;
}
details {
  margin-top: 12px;
  color: #758477;
  font-size: 11px;
}
.detail-chunk {
  margin-top: 16px;
  border: 1px solid #e7ece4;
  padding: 16px;
  border-radius: 8px;
}
.highlighted {
  background: #f0f7df;
  border-color: #9fb780;
}
a {
  color: #3e6a4e;
}
@media (max-width: 850px) {
  .kb-layout > div { display: contents; }
  .search-card { order: 1; }
  .document-card { order: 2; }
  .knowledge-page {
    padding: 18px;
  }
  .kb-layout {
    grid-template-columns: 1fr;
  }
  .kb-header {
    align-items: flex-start;
  }
  .kb-header h1 {
    font-size: 28px;
  }
  .document-row {
    align-items: flex-start;
    flex-direction: column;
  }
  .kb-card {
    padding: 18px;
  }
  .agent-link {
    font-size: 11px;
  }
}
</style>
