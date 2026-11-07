<template>
  <section class="agent-page">
    <header class="agent-header">
      <div>
        <router-link v-if="standalone" class="back-link" to="/home"
          >← 返回工作台</router-link
        >
        <p class="eyebrow">NUTRITRACK / HEALTH AGENT</p>
        <h1>让每一次饮食，都有据可循。</h1>
        <p class="subtitle">
          查记录、看趋势、找依据，和你的健康助手一起把建议落到日常。
        </p>
      </div>
      <div class="mode-status" :class="status.mode" role="status">
        <span class="status-dot"></span>
        {{ statusLoading ? "连接中" : modeLabel(status.mode) }}
      </div>
    </header>

    <div v-if="statusError" class="notice notice-error" role="alert">
      <span>{{ statusError }}</span>
      <button class="text-button" :disabled="statusLoading" @click="loadStatus">
        重新连接
      </button>
    </div>
    <div v-else-if="status.mode === 'demo'" class="notice notice-demo">
      <span class="notice-label">演示模式</span>
      使用规则流程调用真实饮食查询和知识检索工具，不调用大模型。个性化对话需配置模型后开启。
    </div>
    <div
      v-else-if="!statusLoading && status.mode === 'unavailable'"
      class="notice notice-error"
      role="alert"
    >
      模型服务尚未配置。请完成服务端模型配置，或显式开启演示模式后重试。
    </div>

    <div class="agent-layout">
      <main class="chat-panel">
        <div class="panel-heading">
          <div class="assistant-identity">
            <span class="assistant-mark" aria-hidden="true"
              ><el-icon><ChatDotRound /></el-icon
            ></span>
            <div>
              <h2>健康助手</h2>
              <p>连接你的饮食记录与营养知识</p>
            </div>
          </div>
          <button
            class="reset-button"
            :disabled="busy || restoring || resetting || !messages.length"
            @click="resetSession"
          >
            <el-icon><RefreshRight /></el-icon
            >{{ resetting ? "清理中" : "新对话" }}
          </button>
        </div>

        <div
          ref="conversationEl"
          class="conversation"
          role="log"
          aria-label="与健康助手的对话"
          :aria-busy="busy || restoring"
        >
          <div v-if="restoring" class="empty-state">
            <p>正在恢复当前对话…</p>
          </div>
          <div v-else-if="!messages.length" class="empty-state">
            <span class="welcome-mark" aria-hidden="true"
              ><el-icon><Bowl /></el-icon
            ></span>
            <h3>今天，从一个小问题开始</h3>
            <p>
              我会按问题查询你的记录或检索资料，并在回答后展示使用的工具与参考来源。
            </p>
            <div class="starter-grid">
              <button
                v-for="item in starters"
                :key="item.title"
                :disabled="busy || !canChat"
                @click="useStarter(item.question)"
              >
                <span class="starter-title"
                  >{{ item.title }}<span aria-hidden="true">↗</span></span
                >
                <span>{{ item.question }}</span>
              </button>
            </div>
          </div>

          <article
            v-for="(message, index) in messages"
            :key="message.id || index"
            class="message"
            :class="message.role"
          >
            <div class="message-label">
              {{ message.role === "user" ? "你" : "健康助手" }}
              <span v-if="message.mode" class="message-mode">{{
                modeLabel(message.mode)
              }}</span>
            </div>
            <div class="message-content">{{ message.content }}</div>
            <details v-if="message.sources?.length" class="message-sources">
              <summary>参考资料 · {{ message.sources.length }} 条</summary>
              <div
                v-for="(source, sourceIndex) in message.sources"
                :key="`${source.id}-${sourceIndex}`"
                class="source-item"
              >
                <span class="source-id">[{{ source.id }}]</span>
                <div>
                  <a
                    v-if="safeSourceUrl(source.url)"
                    :href="safeSourceUrl(source.url)"
                    target="_blank"
                    rel="noopener noreferrer"
                    >{{ source.title }} ↗</a
                  >
                  <span v-else>{{ source.title }}</span>
                  <router-link
                    v-if="source.documentId"
                    class="source-location"
                    :to="{ path: '/knowledge', query: { documentId: source.documentId, chunkId: source.chunkId, version: source.documentVersion } }"
                    >查看对应文段 · 第 {{ source.page }} 页 →</router-link
                  >
                  <p>{{ source.excerpt }}</p>
                </div>
              </div>
            </details>
          </article>

          <div v-if="busy" class="working-state" role="status">
            <span class="working-dot"></span>正在查询与整理，请稍候…
          </div>
        </div>

        <div class="composer-area">
          <div v-if="chatError" class="chat-error" role="alert">
            {{ chatError }}
          </div>
          <form class="composer" @submit.prevent="sendMessage">
            <label class="sr-only" for="agent-message">向健康助手提问</label>
            <textarea
              id="agent-message"
              ref="inputEl"
              v-model="draft"
              rows="3"
              maxlength="2000"
              :disabled="busy || restoring || resetting || !canChat"
              placeholder="例如：总结我最近 7 天的饮食，并给出少盐建议"
              @keydown.ctrl.enter.prevent="sendMessage"
              @keydown.meta.enter.prevent="sendMessage"
            ></textarea>
            <div class="composer-footer">
              <span
                >{{ draft.length }}/2000
                <span class="keyboard-hint">· Ctrl / ⌘ + Enter 发送</span></span
              >
              <button
                class="send-button"
                type="submit"
                :disabled="
                  !draft.trim() || busy || restoring || resetting || !canChat
                "
              >
                {{ busy ? "处理中" : "发送"
                }}<el-icon v-if="!busy"><TopRight /></el-icon>
              </button>
            </div>
          </form>
          <p class="health-note">
            建议用于日常饮食管理；疾病诊断、用药及特殊人群的饮食方案请咨询专业人员。
          </p>
        </div>
      </main>

      <aside class="context-column">
        <section class="context-card capabilities">
          <p class="card-eyebrow">为你的问题寻找依据</p>
          <h2>从记录到行动</h2>
          <router-link to="/knowledge">管理我的知识资料 ↗</router-link>
          <div v-for="item in capabilities" :key="item.tool" class="capability">
            <span class="capability-icon" aria-hidden="true"
              ><el-icon><component :is="item.icon" /></el-icon
            ></span>
            <div>
              <h3>{{ item.title }}</h3>
              <p>{{ item.description }}</p>
            </div>
          </div>
          <p class="scope-note">
            仅查询当前登录用户的数据。对话保留在服务端临时会话中，可通过“新对话”清理。
          </p>
        </section>

        <section class="context-card execution-card">
          <div class="execution-heading">
            <h2>本次工具执行</h2>
            <span v-if="lastResult"
              >{{ lastResult.steps?.length || 0 }} 次</span
            >
          </div>
          <div v-if="busy" class="execution-placeholder">
            完成本次回答后，将在这里展示执行记录。
          </div>
          <template v-else-if="lastResult">
            <p v-if="!lastResult.steps?.length" class="execution-placeholder">
              本次回答没有调用工具。
            </p>
            <ol v-else class="execution-list">
              <li v-for="(step, index) in lastResult.steps" :key="index">
                <span
                  class="step-number"
                  :class="{ failed: !stepSucceeded(step.status) }"
                  >{{ index + 1 }}</span
                >
                <div class="step-body">
                  <div class="step-title">
                    <h3>{{ toolLabel(step.tool) }}</h3>
                    <span>{{ formatDuration(step.durationMs) }}</span>
                  </div>
                  <span
                    class="step-status"
                    :class="{ failed: !stepSucceeded(step.status) }"
                    >{{
                      stepSucceeded(step.status) ? "已完成" : "未完成"
                    }}</span
                  >
                  <p v-if="step.summary">{{ step.summary }}</p>
                  <details v-if="step.arguments">
                    <summary>查询参数</summary>
                    <pre>{{ formatArguments(step.arguments) }}</pre>
                  </details>
                </div>
              </li>
            </ol>
            <div v-if="lastResult.usage" class="usage-row">
              <span
                >总耗时 {{ formatDuration(lastResult.usage.durationMs) }}</span
              >
              <span>模型调用 {{ lastResult.usage.modelCalls || 0 }} 次</span>
            </div>
          </template>
          <div v-else class="execution-placeholder">
            发起对话后，可在这里查看助手查了哪些记录、检索了什么资料。
          </div>
        </section>
      </aside>
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import {
  Bowl,
  ChatDotRound,
  Collection,
  DataLine,
  Notebook,
  RefreshRight,
  TopRight,
} from "@element-plus/icons-vue";
import { useUserStore } from "@/store/user";
import {
  deleteAgentSession,
  getAgentSession,
  getAgentStatus,
  sendAgentMessage,
} from "@/api/agent";

const route = useRoute();
const userStore = useUserStore();
const standalone = computed(() => route.path === "/health-agent");
const status = ref({ mode: "unavailable" });
const statusLoading = ref(true);
const statusError = ref("");
const chatError = ref("");
const busy = ref(false);
const restoring = ref(false);
const resetting = ref(false);
const messages = ref([]);
const draft = ref("");
const sessionId = ref("");
const lastResult = ref(null);
const conversationEl = ref(null);
const inputEl = ref(null);
const controller = new AbortController();
const sessionKey = `nutritrack.agent.session.${encodeURIComponent(userStore.username)}`;
const canChat = computed(
  () =>
    !statusLoading.value &&
    !statusError.value &&
    ["demo", "live"].includes(status.value.mode),
);

const starters = [
  { title: "回顾这一周", question: "帮我总结最近 7 天的饮食记录。" },
  { title: "看看今天", question: "查看今天的饮食记录，有哪些需要关注的地方？" },
  {
    title: "找到饮食依据",
    question: "日常饮食怎样减少盐和糖？请给出参考来源。",
  },
  {
    title: "从一餐开始改善",
    question: "如何搭配一顿均衡的早餐？请给出参考来源。",
  },
];
const capabilities = [
  {
    tool: "get_meals",
    title: "读取饮食记录",
    description: "按日期查看已经记录的餐食。",
    icon: Notebook,
  },
  {
    tool: "summarize_meals",
    title: "汇总记录趋势",
    description: "回顾一段时间的记录与热量数据。",
    icon: DataLine,
  },
  {
    tool: "search_knowledge",
    title: "检索营养知识",
    description: "从公共资料和你导入的文档中寻找依据。",
    icon: Collection,
  },
];

function modeLabel(mode) {
  return (
    { live: "模型模式", demo: "工具演示", unavailable: "暂不可用" }[mode] ||
    "状态未知"
  );
}
function toolLabel(tool) {
  return capabilities.find((item) => item.tool === tool)?.title || tool;
}
function stepSucceeded(value) {
  return ["success", "completed", "ok"].includes(String(value).toLowerCase());
}
function formatDuration(value) {
  if (!Number.isFinite(Number(value))) return "—";
  return Number(value) < 1000
    ? `${value} ms`
    : `${(Number(value) / 1000).toFixed(1)} s`;
}
function formatArguments(value) {
  if (typeof value !== "string") return JSON.stringify(value, null, 2);
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}
function safeSourceUrl(value) {
  try {
    const url = new URL(value);
    return ["http:", "https:"].includes(url.protocol) ? url.href : "";
  } catch {
    return "";
  }
}
function errorMessage(error, fallback) {
  const code = error.response?.status || error.code;
  if (code === "ERR_NETWORK") return fallback;
  if (code === 401 || code === 403) return "登录状态已失效，请重新登录后再试。";
  if (code === 404) return "会话不存在或已过期，请点击“新对话”后重试。";
  if (code === 409) return "当前会话正在处理另一条消息，请稍后重试。";
  if (code === "ECONNABORTED" || code === 504)
    return "本次请求超时，请稍后重试；若服务端已完成，可重新进入页面恢复对话。";
  return error.response?.data?.message || error.message || fallback;
}
function saveSession(value) {
  sessionId.value = value || "";
  try {
    if (value) sessionStorage.setItem(sessionKey, value);
    else sessionStorage.removeItem(sessionKey);
  } catch {
    /* Browser storage is optional. Chat content is never stored here. */
  }
}
async function scrollToEnd() {
  await nextTick();
  conversationEl.value?.scrollTo({
    top: conversationEl.value.scrollHeight,
    behavior: "smooth",
  });
}
async function loadStatus() {
  statusLoading.value = true;
  statusError.value = "";
  try {
    status.value = await getAgentStatus(controller.signal);
  } catch (error) {
    if (!controller.signal.aborted)
      statusError.value = errorMessage(
        error,
        "无法连接健康助手，请检查后端服务。",
      );
  } finally {
    statusLoading.value = false;
  }
}
async function restoreSession() {
  let storedId;
  try {
    storedId = sessionStorage.getItem(sessionKey);
  } catch {
    return;
  }
  if (!storedId) return;
  restoring.value = true;
  try {
    const result = await getAgentSession(storedId, controller.signal);
    messages.value = (result.messages || []).filter((item) =>
      ["user", "assistant"].includes(item.role),
    );
    saveSession(result.sessionId || storedId);
    await scrollToEnd();
  } catch (error) {
    if (controller.signal.aborted) return;
    if ((error.response?.status || error.code) === 404) saveSession("");
    else chatError.value = errorMessage(error, "恢复会话失败，请重试。");
  } finally {
    restoring.value = false;
  }
}
async function useStarter(question) {
  draft.value = question;
  await nextTick();
  inputEl.value?.focus();
}
async function sendMessage() {
  const message = draft.value.trim();
  if (
    !message ||
    busy.value ||
    restoring.value ||
    resetting.value ||
    !canChat.value
  )
    return;
  busy.value = true;
  chatError.value = "";
  draft.value = "";
  messages.value.push({ role: "user", content: message });
  await scrollToEnd();
  try {
    const result = await sendAgentMessage(
      { message, sessionId: sessionId.value },
      controller.signal,
    );
    saveSession(result.sessionId);
    lastResult.value = result;
    messages.value.push({
      role: "assistant",
      content: result.answer,
      sources: result.sources,
      mode: result.mode,
    });
  } catch (error) {
    if (controller.signal.aborted) return;
    messages.value.pop();
    draft.value = message;
    chatError.value = errorMessage(error, "本次提问失败，请重试。");
  } finally {
    busy.value = false;
    if (!controller.signal.aborted) {
      await scrollToEnd();
      inputEl.value?.focus();
    }
  }
}
async function resetSession() {
  if (busy.value || resetting.value || restoring.value) return;
  resetting.value = true;
  chatError.value = "";
  try {
    if (sessionId.value)
      await deleteAgentSession(sessionId.value, controller.signal);
  } catch (error) {
    if (controller.signal.aborted) return;
    if ((error.response?.status || error.code) !== 404) {
      chatError.value = errorMessage(error, "清理会话失败，请重试。");
      return;
    }
  } finally {
    resetting.value = false;
  }
  saveSession("");
  messages.value = [];
  lastResult.value = null;
  draft.value = "";
  inputEl.value?.focus();
}

onMounted(() => {
  loadStatus();
  restoreSession();
});
onBeforeUnmount(() => controller.abort());
</script>

<style scoped>
.source-location { display: block; margin-top: 6px; font-size: 12px; }
.agent-page {
  font-family: system-ui, -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif;
  padding: 36px clamp(18px, 3vw, 48px);
  max-width: 1560px;
  margin: 0 auto;
  color: #20342f;
}
.agent-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 24px;
  margin-bottom: 24px;
}
.eyebrow,
.card-eyebrow {
  font-size: 11px;
  letter-spacing: 1.8px;
  font-weight: 700;
  color: #658478;
  margin: 0 0 12px;
}
.agent-header h1 {
  margin: 0;
  font-size: clamp(23px, 2.2vw, 32px);
  line-height: 1.5;
  letter-spacing: -0.7px;
  font-weight: 650;
}
.subtitle {
  margin: 10px 0 0;
  font-size: 13px;
  color: #6b7b76;
  line-height: 1.8;
}
.back-link {
  display: inline-block;
  margin-bottom: 18px;
  color: #39775e;
  font-size: 13px;
  text-decoration: none;
}
.mode-status {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-shrink: 0;
  margin-top: 10px;
  padding: 8px 11px;
  border: 1px solid #dce5e0;
  border-radius: 20px;
  background: #fff;
  color: #697c72;
  font-size: 12px;
}
.status-dot {
  width: 6px;
  height: 6px;
  background: #97a49d;
  border-radius: 50%;
}
.mode-status.live {
  color: #34775c;
  border-color: #cfe6d7;
}
.live .status-dot {
  background: #4a9565;
}
.mode-status.demo {
  color: #96672d;
  border-color: #eadac1;
}
.demo .status-dot {
  background: #b68d4c;
}
.notice {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
  padding: 12px 16px;
  border-radius: 10px;
  font-size: 12px;
  line-height: 1.8;
}
.notice-demo {
  color: #86632f;
  background: #faf6ec;
  border: 1px solid #eee4cb;
}
.notice-label {
  font-weight: 700;
  white-space: nowrap;
}
.notice-error {
  color: #a54e43;
  background: #fff3f0;
  border: 1px solid #f1d7d0;
  justify-content: space-between;
}
.text-button {
  border: none;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
  text-decoration: underline;
  white-space: nowrap;
}
.agent-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 290px;
  align-items: start;
  gap: 22px;
}
.chat-panel {
  background: #fff;
  border: 1px solid #e0e7e2;
  border-radius: 16px;
  overflow: hidden;
  min-width: 0;
  box-shadow: 0 4px 20px #20342f03;
}
.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 24px;
  border-bottom: 1px solid #edf0ed;
}
.assistant-identity {
  display: flex;
  align-items: center;
  gap: 11px;
}
.assistant-mark {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 12px;
  background: #e8f2eb;
  color: #42795b;
  font-size: 20px;
}
h2 {
  font-size: 14px;
  margin: 0;
  font-weight: 650;
}
.assistant-identity p {
  margin: 5px 0 0;
  color: #89968e;
  font-size: 11px;
}
.reset-button {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 7px 10px;
  font: inherit;
  font-size: 12px;
  color: #62776a;
  background: #fff;
  border: 1px solid #e0e7e2;
  border-radius: 7px;
  cursor: pointer;
  white-space: nowrap;
}
button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
button:focus-visible,
a:focus-visible,
summary:focus-visible {
  outline: 2px solid #5b9071;
  outline-offset: 3px;
}
.conversation {
  min-height: 390px;
  height: clamp(390px, 52vh, 660px);
  overflow-y: auto;
  padding: 26px 24px;
  scroll-behavior: smooth;
}
.empty-state {
  padding: 28px 4px 18px;
  text-align: center;
}
.welcome-mark {
  display: inline-grid;
  place-items: center;
  width: 60px;
  height: 60px;
  border-radius: 22px;
  background: #f0f5ed;
  color: #739561;
  font-size: 30px;
}
.empty-state h3 {
  margin: 20px 0 12px;
  font-size: 20px;
  font-weight: 600;
}
.empty-state > p {
  font-size: 12px;
  line-height: 1.9;
  color: #839187;
  max-width: 380px;
  margin: 0 auto;
}
.starter-grid {
  margin: 28px auto 0;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  max-width: 520px;
}
.starter-grid button {
  display: flex;
  flex-direction: column;
  gap: 10px;
  text-align: left;
  padding: 14px;
  border: 1px solid #e5ebe4;
  border-radius: 10px;
  background: #fbfcfa;
  color: #7c8c7f;
  font: inherit;
  font-size: 11px;
  line-height: 1.7;
  cursor: pointer;
  transition:
    border-color 0.2s,
    background 0.2s;
}
.starter-grid button:hover:enabled {
  border-color: #b4cbb5;
  background: #f4f8f0;
}
.starter-title {
  display: flex;
  width: 100%;
  justify-content: space-between;
  color: #46624e;
  font-size: 12px;
  font-weight: 600;
}
.message {
  margin-bottom: 24px;
}
.message-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  font-weight: 650;
  color: #577963;
  margin-bottom: 8px;
}
.message.user .message-label {
  justify-content: flex-end;
  color: #8a968d;
}
.message-content {
  font-size: 13px;
  line-height: 1.95;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.message.user .message-content {
  margin-left: auto;
  width: fit-content;
  max-width: 90%;
  padding: 12px 16px;
  border-radius: 12px 12px 0 12px;
  background: #eff4ef;
  color: #365840;
}
.message-mode {
  font-size: 10px;
  color: #8b958e;
  font-weight: 400;
  border: 1px solid #e6ebe4;
  padding: 1px 5px;
  border-radius: 4px;
}
.message-sources {
  margin-top: 12px;
  border: 1px solid #e5ebe4;
  border-radius: 8px;
  padding: 10px 12px;
  background: #fcfdfb;
}
.message-sources summary {
  cursor: pointer;
  color: #66816c;
  font-size: 11px;
}
.source-item {
  display: flex;
  gap: 9px;
  margin-top: 12px;
  font-size: 11px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.source-id {
  color: #839788;
  flex-shrink: 0;
}
.source-item a {
  color: #39785b;
  text-decoration: none;
}
.source-item a:hover {
  text-decoration: underline;
}
.source-item p {
  color: #7e8f82;
  margin: 5px 0 0;
}
.working-state {
  color: #718c77;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
}
.working-dot {
  width: 7px;
  height: 7px;
  background: #6c9975;
  border-radius: 50%;
  animation: breathe 1.4s infinite;
}
.composer-area {
  padding: 0 20px 15px;
}
.composer {
  border: 1px solid #dce6da;
  border-radius: 11px;
  background: #fff;
  padding: 12px;
  transition: border-color 0.2s;
}
.composer:focus-within {
  border-color: #8fb294;
}
.composer textarea {
  display: block;
  box-sizing: border-box;
  width: 100%;
  border: none;
  outline: none;
  resize: vertical;
  min-height: 66px;
  max-height: 180px;
  font: inherit;
  font-size: 13px;
  color: #304e3c;
  line-height: 1.8;
  background: transparent;
}
.composer textarea::placeholder {
  color: #a0aca1;
}
.composer textarea:disabled {
  opacity: 0.7;
}
.composer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 8px;
}
.composer-footer > span {
  font-size: 10px;
  color: #a3afa3;
}
.send-button {
  display: flex;
  align-items: center;
  gap: 10px;
  border: none;
  background: #47785c;
  color: white;
  border-radius: 7px;
  padding: 9px 14px;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.send-button:hover:enabled {
  background: #38634b;
}
.health-note {
  margin: 10px 0 0;
  text-align: center;
  font-size: 10px;
  color: #9aa49c;
  line-height: 1.8;
}
.chat-error {
  padding: 0 2px 12px;
  font-size: 12px;
  color: #b46351;
  line-height: 1.8;
}
.context-column {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.context-card {
  background: #fff;
  border: 1px solid #e0e7e2;
  border-radius: 14px;
  padding: 21px;
}
.capabilities {
  background: #f1f5ef;
  border-color: #e1e9dc;
}
.card-eyebrow {
  font-size: 10px;
  letter-spacing: 1px;
  margin-bottom: 8px;
}
.capabilities > h2 {
  font-size: 19px;
  margin-bottom: 22px;
}
.capability {
  display: flex;
  gap: 11px;
  margin-top: 20px;
}
.capability-icon {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  background: #e6eddf;
  border-radius: 9px;
  color: #668657;
  flex-shrink: 0;
}
.capability h3 {
  font-size: 12px;
  font-weight: 600;
  margin: 1px 0 6px;
}
.capability p {
  font-size: 11px;
  line-height: 1.8;
  color: #84927d;
  margin: 0;
}
.scope-note {
  font-size: 10px;
  line-height: 1.9;
  color: #899680;
  border-top: 1px solid #dfe7d7;
  margin: 22px 0 0;
  padding-top: 15px;
}
.execution-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.execution-heading > span {
  font-size: 11px;
  color: #8b9c8e;
}
.execution-placeholder {
  font-size: 11px;
  line-height: 1.9;
  color: #9aa69c;
  margin: 20px 0 4px;
}
.execution-list {
  list-style: none;
  padding: 0;
  margin: 22px 0 0;
}
.execution-list li {
  display: flex;
  gap: 10px;
  margin-bottom: 22px;
}
.step-number {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  flex-shrink: 0;
  background: #eaf3e7;
  color: #628452;
  font-size: 10px;
}
.step-body {
  min-width: 0;
  flex: 1;
}
.step-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 5px;
}
.step-title h3 {
  font-size: 11px;
  margin: 2px 0 7px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.step-title > span {
  font-size: 10px;
  color: #a0aca0;
  white-space: nowrap;
}
.step-status {
  font-size: 10px;
  color: #749163;
}
.step-status.failed,
.step-number.failed {
  color: #b0764c;
}
.step-number.failed {
  background: #faf1e7;
}
.step-body p {
  margin: 7px 0 0;
  font-size: 10px;
  line-height: 1.9;
  color: #8b9a8d;
  overflow-wrap: anywhere;
}
.step-body details {
  margin-top: 8px;
  font-size: 10px;
  color: #8b9a8d;
}
.step-body summary {
  cursor: pointer;
}
.step-body pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #f6f8f4;
  border-radius: 5px;
  padding: 8px;
  font-size: 10px;
  line-height: 1.7;
}
.usage-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  border-top: 1px solid #edf0e9;
  padding-top: 12px;
  font-size: 10px;
  color: #94a08f;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
@keyframes breathe {
  50% {
    opacity: 0.35;
  }
}
@media (prefers-reduced-motion: reduce) {
  .working-dot {
    animation: none;
  }
  .conversation {
    scroll-behavior: auto;
  }
}
@media (max-width: 1140px) {
  .agent-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .context-column {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }
  .conversation {
    height: 480px;
  }
  .agent-page {
    padding: 28px 22px;
  }
}
@media (max-width: 650px) {
  .agent-page {
    padding: 24px 14px;
  }
  .agent-header {
    flex-direction: column;
    gap: 4px;
    margin-bottom: 18px;
  }
  .agent-header h1 {
    font-size: 24px;
  }
  .mode-status {
    margin-top: 6px;
  }
  .notice {
    align-items: flex-start;
    gap: 8px;
  }
  .notice-demo {
    flex-direction: column;
  }
  .agent-layout {
    gap: 16px;
  }
  .panel-heading {
    padding: 16px;
  }
  .conversation {
    padding: 20px 16px;
    height: 450px;
  }
  .empty-state {
    padding-top: 14px;
  }
  .empty-state h3 {
    font-size: 18px;
  }
  .starter-grid {
    grid-template-columns: 1fr;
    gap: 8px;
    margin-top: 22px;
  }
  .starter-grid button {
    gap: 3px;
    padding: 11px 13px;
  }
  .context-column {
    grid-template-columns: 1fr;
    gap: 16px;
  }
  .context-card {
    padding: 18px;
  }
  .composer-area {
    padding: 0 12px 12px;
  }
  .keyboard-hint {
    display: none;
  }
  .health-note {
    font-size: 10px;
  }
  .subtitle {
    font-size: 12px;
  }
}
</style>
